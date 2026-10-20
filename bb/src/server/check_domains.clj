(ns server.check-domains
  "Guards this repository's host neutrality: no tracked file names a production
   domain, a consuming host's development hostnames, or a consuming host's own
   systems. dromon is an independent FHIR server, so its fixtures, docs and
   defaults read localhost, and what a host needs beyond the FHIR
   specification reaches dromon through an option, not through dromon's code
   naming the host.

   Two kinds of hit:

   - a host name (`domain-parts`): the production domain or a consumer's
     `local*` development hostname. Never allowed.
   - an implementation name (`name-parts`): a consuming host's systems,
     packages, Keto namespaces or realms. Allowed only where the baseline
     (`baseline-path`) still lists the line, so existing references can be
     removed over time while a new one fails the check.

   The Java package and Maven group `com.breezeehr` and the GitHub organization
   that hosts this repository are ownership, not implementation, and are not
   hits (`exempt-parts`).

   `bb check-domains` greps the tracked tree (git grep, so ignored and
   untracked files are not looked at) and fails on a hit the baseline does not
   cover, and on a baseline entry that no longer matches anything (delete it,
   or run `bb check-domains --prune`, which only ever removes entries).

   The patterns are assembled from parts so that this file, the test and the
   bb.edn entry do not match the patterns they enforce."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [process]]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:private domain-parts
  [["bree" "zeehr" "\\." "com"]
   ["local" "(hydra|kratos|keto|auth|flot" "illa|ji" "b3|dromon|new" "care|portal|care)"]])

(def ^:private name-parts
  "Each entry joins into one name. The first is matched anywhere in a word
   (it prefixes every package and namespace of the host); the rest only as
   whole words."
  [["bree" "ze"]
   ["flot" "illa"] ["jib" "[0-9]"] ["ma" "st"] ["pur" "ser"] ["fu" "rl"] ["py" "r"]
   ["galley" "2"] ["cabo" "tage2?"] ["gang" "way"] ["dec" "ant(ed|s|ing)?"]
   ["master-" "at-arms"] ["m" "a2"] ["fhir-" "igs"] ["dromon-" "datomic"]
   ["fhir-store-" "datomic"] ["pg" "oa"] ["us" "ep"] ["practitioner" "-id"]
   ["login-" "consent"] ["cookie-" "authentication"] ["rcm-" "design"]
   ["rcm-" "x12"] ["new" "care"] ["md" "toolbox"] ["phi-" "fhir"]])

(def ^:private exempt-parts
  "Ownership names, removed from a line before it is matched against the
   implementation names."
  [["(?i)\\bcom[./_]" "bree" "zeehr\\b"]
   ["\\b" "Bree" "zeemr/"]])

(def baseline-path
  "The baseline, relative to the repository root. Excluded from the scan."
  "bb/check-domains-baseline.edn")

(defn- joined [parts] (map #(apply str %) parts))

(defn pattern
  "The extended regular expression `git grep -E` is given: every host name and
   implementation name, unanchored. A candidate filter; [[hit-kinds]] decides."
  []
  (str/join "|" (concat (joined domain-parts) (joined name-parts))))

(def ^:private domain-re
  (re-pattern (str "(?i)" (str/join "|" (joined domain-parts)))))

(def ^:private name-re
  (let [[prefix & words] (joined name-parts)]
    (re-pattern (str "(?i)" prefix "|\\b(?:" (str/join "|" words) ")\\b"))))

(def ^:private exempt-res
  (mapv #(re-pattern (apply str %)) exempt-parts))

(defn hit-kinds
  "The kinds of hit `text` (one line) carries: a set of :domain and :name,
   empty when it carries neither once ownership names are set aside."
  [text]
  (cond-> #{}
    (re-find domain-re text) (conj :domain)
    (re-find name-re (reduce #(str/replace %1 %2 " ") text exempt-res)) (conj :name)))

(defn- parse-line
  "`path:line:text` from git grep -n, or nil."
  [s]
  (when-let [[_ path line text] (re-matches #"(?s)([^:]+):(\d+):(.*)" s)]
    {:path path :line (parse-long line) :text text}))

(defn- git-root [dir]
  (let [{:keys [exit out]} @(process (cond-> ["git"]
                                       dir (into ["-C" dir])
                                       :always (into ["rev-parse" "--show-toplevel"]))
                                     {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn- grep-hits
  "{:status :ok|:error, :hits [{:path :line :text :kinds}], :err s}."
  [dir]
  (let [{:keys [exit out err]}
        @(process (cond-> ["git"]
                    dir (into ["-C" dir])
                    :always (into ["grep" "-n" "-i" "-I" "--full-name" "-E" "-e" (pattern)
                                   "--" ":/" (str ":(exclude,top)" baseline-path)]))
                  {:out :string :err :string})]
    (case (int exit)
      0 {:status :ok
         :hits (into []
                     (comp (keep parse-line)
                           (map #(assoc % :kinds (hit-kinds (:text %))))
                           (filter (comp seq :kinds)))
                     (str/split-lines out))}
      1 {:status :ok :hits []}
      {:status :error :err err})))

(defn- entry [{:keys [path text]}] [path (str/trim text)])

(defn read-baseline
  "The baseline's [path line-text] entries under `root`, or [] when it is
   absent."
  [root]
  (let [f (fs/path root baseline-path)]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) [])))

(defn write-baseline!
  "Writes `entries`, sorted, one per line."
  [root entries]
  (spit (str (fs/path root baseline-path))
        (str ";; Lines that name a consuming host's systems and predate the check.\n"
             ";; `bb check-domains` allows them and nothing else; delete an entry\n"
             ";; once its line no longer names the host. See server.check-domains.\n"
             (with-out-str (pprint/pprint (vec (sort entries)))))))

(defn scan
  "Runs the check over the tracked tree of the repository containing `dir`
   (default: the working directory), whichever subdirectory that is. Returns

     {:status :clean|:hits|:error, :out s, :err s,
      :new [hit ...], :stale [entry ...], :root s}

   :hits when a domain hit exists, an implementation-name hit is not covered
   by the baseline, or a baseline entry matches nothing. Each baseline entry
   covers one occurrence of its line in its file.

   git grep exits 0 on a match, 1 on none and above 1 on a failure (not a
   repository, a bad pattern), so the three are told apart rather than read as
   a boolean: treating every non-zero exit as clean would let a broken run
   pass."
  ([] (scan nil))
  ([dir]
   (let [root (git-root dir)
         {:keys [status hits err]} (if root
                                     (grep-hits dir)
                                     {:status :error :err (str "not a git repository: " dir)})]
     (if (= :error status)
       {:status :error :out "" :err err}
       (let [baseline  (frequencies (read-baseline root))
             [new left] (reduce (fn [[new left] hit]
                                  (let [e (entry hit)]
                                    (if (and (= #{:name} (:kinds hit)) (pos? (get left e 0)))
                                      [new (update left e dec)]
                                      [(conj new hit) left])))
                                [[] baseline]
                                hits)
             stale     (vec (sort (mapcat (fn [[e n]] (repeat n e)) (filter (comp pos? val) left))))
             out       (str/join "\n" (concat
                                       (map (fn [{:keys [path line text]}]
                                              (str path ":" line ":" text))
                                            new)
                                       (map (fn [[path text]]
                                              (str "stale baseline entry: " path ": " text))
                                            stale)))]
         {:status (if (or (seq new) (seq stale)) :hits :clean)
          :out out :err "" :new new :stale stale :root root})))))

(defn -main
  "Prints each hit and returns the process exit code: 0 clean, 1 hits, 2 when
   the scan itself failed. `--prune` first removes the baseline entries that
   match nothing."
  [& args]
  (let [prune? (some #{"--prune"} args)
        {:keys [status out err stale root]} (scan)]
    (if (and prune? (= :hits status) (seq stale))
      (let [remove-one (fn [entries e] (let [[a b] (split-with #(not= e %) entries)]
                                         (concat a (rest b))))]
        (write-baseline! root (reduce remove-one (read-baseline root) stale))
        (println "check-domains: pruned" (count stale) "stale baseline entries")
        (apply -main (remove #{"--prune"} args)))
      (case status
        :clean (do (println "check-domains: no hits") 0)
        :hits  (do (println "check-domains: host-specific names in tracked files:")
                   (println (str/trimr out))
                   1)
        (do (println "check-domains: the scan failed:" (str/trim (str err)))
            2)))))
