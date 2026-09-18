(ns server.keto-realm-backfill
  "Backfills the `fhir` Keto namespace from realm-blind objects to
   realm-scoped ones: \"Patient\" -> \"<realm>/Patient\", \"Patient/123\" ->
   \"<realm>/Patient/123\", \"system\" -> \"<realm>/system\".

   Step 3 of the rollout in dromon/docs/keto-realm-scoping.md. Run it only
   after the dual-writing writers and the either-shape reader are deployed:
   before that, a backfilled tuple authorizes nothing, because the old reader
   asks only about the realm-blind object.

   DRY RUN BY DEFAULT. Nothing is written without --apply.

     bb keto-realm-backfill                    ; report only
     bb keto-realm-backfill --apply            ; write the new tuples
     bb keto-realm-backfill --realm dev        ; attribute everything to `dev`
     bb keto-realm-backfill --apply --prune    ; also delete the legacy tuples

   THE SUBJECT SPELLINGS ARE THE TRAP. Realm evidence lives in the
   `breezeehr-role` and `practitioner-id` namespaces, which spell their subject
   `user:/<kratos-id>`, while the `fhir` tuple being backfilled spells the same
   person as a bare `<kratos-id>`. This namespace bridges the two explicitly,
   in `subject->realms`, and nowhere else. A version of this script that
   treated the families uniformly would attribute nothing and report a
   confident zero.

   Subjects are NEVER rewritten. Only objects change."
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private keto-read  (or (System/getenv "KETO_URL") "http://localhost:4466"))
(def ^:private keto-admin (or (System/getenv "KETO_ADMIN_URL") "http://localhost:4467"))

(def ^:private subject-prefix
  "The prefix the realm-view namespaces carry on their subject ids, and the
   `fhir` namespace does not. See the namespace docstring."
  "user:/")

;; ---------------------------------------------------------------------------
;; Keto access
;; ---------------------------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg data)))

(defn list-all-tuples
  "Every tuple in `namespace`, following `next_page_token` to the end.

   Pages rather than taking one page, because the whole point is a complete
   census: a partial listing would under-report what needs backfilling and the
   operator would read the shortfall as progress. `getRelationships` has no
   prefix or wildcard filter (verified against Keto v0.12.0), so narrowing by
   realm or by object shape is necessarily client-side."
  [namespace & {:keys [relation subject-id page-size] :or {page-size 500}}]
  (loop [token nil, acc []]
    (let [resp (curl/get (str keto-read "/relation-tuples")
                         {:query-params (cond-> {"namespace" namespace
                                                 "page_size" (str page-size)}
                                          relation   (assoc "relation" relation)
                                          subject-id (assoc "subject_id" subject-id)
                                          token      (assoc "page_token" token))
                          :throw false})]
      (when-not (= 200 (:status resp))
        (fail! "Keto listing failed" {:namespace namespace :status (:status resp)
                                      :body (:body resp)}))
      (let [{:keys [relation_tuples next_page_token]}
            (json/parse-string (:body resp) true)
            acc (into acc relation_tuples)]
        (if (and (seq next_page_token) (seq relation_tuples))
          (recur next_page_token acc)
          acc)))))

(defn- put-tuple! [tuple]
  (let [resp (curl/put (str keto-admin "/admin/relation-tuples")
                       {:headers {"Content-Type" "application/json"}
                        :body (json/generate-string tuple)
                        :throw false})]
    (when-not (#{200 201} (:status resp))
      (fail! "Keto tuple write failed" {:tuple tuple :status (:status resp)
                                        :body (:body resp)}))))

(defn- delete-tuple! [{:keys [namespace object relation subject_id]}]
  (let [resp (curl/delete (str keto-admin "/admin/relation-tuples")
                          {:query-params (cond-> {"namespace" namespace
                                                  "object" object
                                                  "relation" relation}
                                           subject_id (assoc "subject_id" subject_id))
                           :throw false})]
    (when-not (#{200 204} (:status resp))
      (fail! "Keto tuple delete failed" {:object object :status (:status resp)
                                         :body (:body resp)}))))

;; ---------------------------------------------------------------------------
;; Shape
;; ---------------------------------------------------------------------------

(defn realm-scoped?
  "Whether `object` already carries one of `realms` as its first segment.

   Tested against the realms actually present in Keto rather than by guessing
   from the object's shape. `<realm>/Patient` and `Patient/123` have the same
   segment count, and no capitalization rule separates them reliably -- a realm
   may be named anything -- so the only sound evidence is the realm set."
  [realms object]
  (let [head (first (str/split (str object) #"/"))]
    (contains? realms head)))

(defn- subject-of
  "The bare subject id of a tuple, or nil when the tuple's subject is a SET.

   A subject-set tuple has no `subject_id` at all. It is carried through the
   backfill unchanged in shape -- its object is rewritten like any other -- but
   it has no kratos id to look a realm up by, so it can only be attributed by
   an explicit --realm."
  [tuple]
  (not-empty (str (:subject_id tuple ""))))

;; ---------------------------------------------------------------------------
;; Realm attribution
;; ---------------------------------------------------------------------------

(defn realms-from-relations
  "The realms a subject's role and practitioner tuples name.

   Pure, so the spelling bridge below is testable without a Keto."
  [role-tuples practitioner-tuples]
  (into (sorted-set)
        (comp (map :object)
              (keep #(first (str/split (str %) #"/" 2)))
              (remove str/blank?))
        (concat role-tuples practitioner-tuples)))

(defn subject->realms
  "The realms a bare `fhir`-namespace subject belongs to, from its role and
   practitioner tuples.

   THE SPELLING BRIDGE, and the only place in this script that crosses between
   the two families. The `fhir` tuple names the subject bare; the realm view
   names the same person `user:/<kratos-id>`. Querying the role namespaces with
   the bare id returns nothing at all -- not an error, an empty list -- so the
   failure mode is a silent zero rather than a crash."
  [bare-subject]
  (let [prefixed (str subject-prefix bare-subject)]
    (realms-from-relations
     (list-all-tuples "breezeehr-role" :relation "has-role" :subject-id prefixed)
     (list-all-tuples "practitioner-id" :relation "isa" :subject-id prefixed))))

(defn known-realms
  "Every realm named by any role or practitioner tuple. Used to tell an
   already-scoped object from a legacy one."
  []
  (realms-from-relations
   (list-all-tuples "breezeehr-role" :relation "has-role")
   (list-all-tuples "practitioner-id" :relation "isa")))

;; ---------------------------------------------------------------------------
;; Plan
;; ---------------------------------------------------------------------------

(defn tuple-key
  "The identity a tuple has for our purposes: object, relation and whichever
   subject form it carries.

   Needed because KETO'S WRITE IS NOT AN UPSERT. Three identical PUTs of one
   tuple produce three rows (verified against v0.12.0; `PATCH ... insert`
   behaves the same), since a relationship's primary key is a generated id
   rather than the tuple itself. Checks still answer true, so the duplication
   is invisible until a listing hits its page cap and starts truncating real
   grants. A backfill is exactly the kind of script that gets re-run, so it
   has to do its own existence test."
  [t]
  [(:object t) (:relation t) (:subject_id t) (:subject_set t)])

(defn plan
  "The backfill plan for one pass, as
   {:already-scoped [..] :to-write [..] :already-backfilled [..]
    :unattributable [..]}.

   `to-write` entries are {:from <tuple> :realms #{..} :tuples [..]}, and
   `:tuples` excludes any tuple already present, so a second run writes
   nothing. A subject in several realms gets one new tuple PER realm: that is
   what it can reach today through the realm-blind tuple, and a backfill must
   not narrow access silently -- an operator who wants it narrowed can revoke
   afterwards, having been told.

   `unattributable` is the interesting output. These are subjects holding
   `fhir` tuples with no role or practitioner tuple to name a realm by: the
   machine accounts (test clients, dev tokens, bulk-export service accounts)
   and any human whose realm view has gone missing. They are reported, never
   guessed at."
  [realms realm-override resolve-realms tuples]
  (let [existing (into #{} (map tuple-key) tuples)]
    (reduce
     (fn [acc tuple]
       (let [object  (:object tuple)
             subject (subject-of tuple)]
         (cond
           (realm-scoped? realms object)
           (update acc :already-scoped conj tuple)

           :else
           (let [rs (cond
                      realm-override #{realm-override}
                      subject        (resolve-realms subject)
                      :else          #{})]
             (if (empty? rs)
               (update acc :unattributable conj tuple)
               (let [wanted (mapv (fn [r] (assoc tuple :object (str r "/" object)))
                                  (sort rs))
                     missing (vec (remove (comp existing tuple-key) wanted))]
                 (if (empty? missing)
                   (update acc :already-backfilled conj tuple)
                   (update acc :to-write conj
                           {:from tuple :realms rs :tuples missing}))))))))
     {:already-scoped [] :to-write [] :already-backfilled [] :unattributable []}
     tuples)))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn- subject-label [tuple]
  (or (subject-of tuple)
      (let [ss (:subject_set tuple)]
        (str "(set) " (:namespace ss) ":" (:object ss) "#" (:relation ss)))))

(defn- report! [{:keys [already-scoped to-write already-backfilled unattributable]}
                {:keys [apply? prune?]}]
  (println)
  (println "=== fhir namespace realm backfill ===")
  (println (format "  already realm-scoped : %d" (count already-scoped)))
  (println (format "  already backfilled   : %d legacy tuples whose scoped form exists"
                   (count already-backfilled)))
  (println (format "  to backfill          : %d legacy tuples -> %d new tuples"
                   (count to-write)
                   (reduce + 0 (map (comp count :tuples) to-write))))
  (println (format "  unattributable       : %d" (count unattributable)))
  (println)

  (when (seq to-write)
    (println "-- would write --")
    (doseq [{:keys [from tuples]} (take 40 to-write)]
      (println (format "  %s #%s @%s" (:object from) (:relation from)
                       (subject-label from)))
      (doseq [t tuples]
        (println (format "      -> %s" (:object t)))))
    (when (> (count to-write) 40)
      (println (format "  ... and %d more" (- (count to-write) 40))))
    (println))

  (when (seq unattributable)
    (println "-- UNATTRIBUTABLE: no role or practitioner tuple names a realm --")
    (println "   These hold realm-blind access and this script will not guess a")
    (println "   realm for them. Decide per subject, then re-run with --realm,")
    (println "   or grant them explicitly. They are the answer to whether any")
    (println "   subject relies on cross-realm access today.")
    (doseq [[subject ts] (sort-by key (group-by subject-label unattributable))]
      (println (format "   %-46s %d tuples: %s"
                       subject
                       (count ts)
                       (str/join ", " (sort (distinct (map :object (take 8 ts))))))))
    (println))

  (println (cond
             (not apply?) "DRY RUN. Nothing was written. Re-run with --apply."
             prune? "APPLIED, and legacy tuples pruned."
             :else (str "APPLIED. Legacy tuples were KEPT -- the reader still "
                        "needs them until its fallback is turned off.")))
  (println))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [args args, opts {}]
    (if-let [a (first args)]
      (case a
        "--apply" (recur (next args) (assoc opts :apply? true))
        "--prune" (recur (next args) (assoc opts :prune? true))
        "--realm" (recur (nnext args) (assoc opts :realm (second args)))
        (fail! "unknown argument" {:arg a}))
      opts)))

(defn run!*
  "Testable core: takes the tuple listing and realm resolution as arguments so
   the plan can be exercised without a Keto."
  [{:keys [realm apply? prune?] :as opts} realms resolve-realms tuples]
  (let [p (plan realms realm resolve-realms tuples)]
    (report! p opts)
    (when apply?
      (doseq [{:keys [tuples]} (:to-write p)]
        (run! put-tuple! tuples))
      (when prune?
        ;; Pruning is separate from writing, and runs only after every new
        ;; tuple is in, so an interrupted run leaves BOTH shapes rather than
        ;; neither. `already-backfilled` is pruned too: those are legacy
        ;; tuples whose scoped form is already present, which is exactly the
        ;; state a re-run after a partial prune leaves behind.
        (doseq [t (concat (map :from (:to-write p)) (:already-backfilled p))]
          (delete-tuple! t))))
    p))

(defn -main [& args]
  (let [opts (parse-args args)
        realms (known-realms)]
    (when (empty? realms)
      (println "WARNING: no realms found in breezeehr-role or practitioner-id.")
      (println "Every object will look legacy and nothing can be attributed.")
      (println "Check KETO_URL, or pass --realm explicitly."))
    (run!* opts realms subject->realms (list-all-tuples "fhir"))
    (System/exit 0)))
