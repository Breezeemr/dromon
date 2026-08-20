(ns com.breezeehr.fhir-defintions-to-malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [com.breezeehr.fhir-shape :as shape]
            [com.breezeehr.fhir-schema-gen :as gen]
            [com.breezeehr.fhir-primitives :as fp]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]))

(deftest representation-props-test
  (testing "xmlAttr"
    (is (= {:xml/attr true
            :fhir/representation ["xmlAttr"]}
           (fdm/representation-props {:representation ["xmlAttr"]}))))
  (testing "xmlText + typeAttr"
    (is (= {:xml/text true
            :xml/type-attr true
            :fhir/representation ["typeAttr" "xmlText"]}
           (fdm/representation-props {:representation ["xmlText" "typeAttr"]}))))
  (testing "xml-choice-group extension"
    (is (= {:xml/choice-group true}
           (fdm/representation-props
            {:extension [{:url "http://hl7.org/fhir/tools/StructureDefinition/xml-choice-group"
                          :valueBoolean true}]}))))
  (testing "empty"
    (is (= {} (fdm/representation-props {})))))

(deftest uri->kw2-cda-test
  (is (= :org.hl7.cda.stds.core.StructureDefinition.IVL-TS/v2-1
         (fdm/uri->kw2 "http://hl7.org/cda/stds/core/StructureDefinition/IVL-TS" "2.1")))
  (is (= :org.hl7.cda.us.ccda.StructureDefinition.USRealmAddress/v2-1
         (fdm/uri->kw2 "http://hl7.org/cda/us/ccda/StructureDefinition/USRealmAddress" "2.1"))))

(deftest shape-url-type-is-ref-test
  (testing "full URL type codes are treated as refs"
    (let [info (shape/field-info
                {:code "http://hl7.org/cda/stds/core/StructureDefinition/ADXP"}
                "1")]
      (is (shape/ref? info))))
  (testing "short FHIR complex types remain refs"
    (is (shape/ref? (shape/field-info {:code "CodeableConcept"} "1"))))
  (testing "primitives are not refs"
    (is (not (shape/ref? (shape/field-info {:code "string"} "1"))))))

(deftest generatable-kinds-rebindable-test
  (is (contains? gen/*generatable-kinds* "resource"))
  (is (not (contains? gen/*generatable-kinds* "logical")))
  (binding [gen/*generatable-kinds* (conj gen/*generatable-kinds* "logical")]
    (is (contains? gen/*generatable-kinds* "logical"))))

(deftest profile-discriminator-dispatch-value-test
  (testing "type=profile reads type.profile, not fixed/pattern"
    (let [slice-path ["Observation" "entryRelationship"]
          sub-elements [{:path ["Observation" "entryRelationship" "observation"]
                         :type [{:code "http://hl7.org/cda/stds/core/StructureDefinition/Observation"
                                 :profile ["http://hl7.org/cda/us/ccda/StructureDefinition/AgeObservation"]}]}
                        {:path ["Observation" "entryRelationship" "act"]
                         :type [{:code "http://hl7.org/cda/stds/core/StructureDefinition/Act"}]}]
          discs [{:type "profile" :path "observation"}
                 {:type "profile" :path "act"}]
          ;; #' via ns-resolve — private helpers under test
          extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'extract-dispatch-value)
          result (extract discs sub-elements slice-path "age")]
      (is (= ["http://hl7.org/cda/us/ccda/StructureDefinition/AgeObservation" nil]
             (:dispatch-value result)))))
  (testing "all-nil profile extraction falls back to slice name"
    (let [extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'extract-dispatch-value)
          result (extract [{:type "profile" :path "observation"}
                           {:type "profile" :path "act"}]
                          []
                          ["Observation" "entryRelationship"]
                          "woundMeasurementObservation")]
      (is (= :woundMeasurementObservation (:dispatch-value result)))))
  (testing "standalone dispatch prefers single profile URL"
    (let [standalone (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'standalone-dispatch-value)
          v (standalone
             [{:path ["Observation" "entryRelationship" "observation"]
               :type [{:profile ["http://hl7.org/cda/us/ccda/StructureDefinition/WoundMeasurementObservation"]}]}]
             ["Observation" "entryRelationship"]
             "woundMeasurementObservation")]
      (is (= "http://hl7.org/cda/us/ccda/StructureDefinition/WoundMeasurementObservation" v)))))

;; ---------------------------------------------------------------------------
;; Narrowed choice elements: a differential that declares a type different from
;; the inherited field schema must descend into the declared type, both in the
;; generation-time :sch and in the emitted :form.
;; ---------------------------------------------------------------------------

(def ^:private ta-kw :org.hl7.test.StructureDefinition.TA/v1-0)
(def ^:private ta-alias-kw :org.hl7.test.StructureDefinition.TA/v2-0)
(def ^:private tb-kw :org.hl7.test.StructureDefinition.TB/v1-0)
(def ^:private tc-kw :org.hl7.test.StructureDefinition.TC/v1-0)
(def ^:private ta-url "http://hl7.org/test/StructureDefinition/TA")
(def ^:private tb-url "http://hl7.org/test/StructureDefinition/TB")
(def ^:private tc-url "http://hl7.org/test/StructureDefinition/TC")

(defn- tree-contains? [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))

(defn- narrowed-choice-scenario
  "Drive update-existing-child-schema the way apply-regular-element does for a
   profile element that narrows a choice field :f (inherited as
   [:sequential [:ref TA]]) to `declared-url` and constrains the narrowed
   type's child .u. Returns the emitted patch acc, the inner descent acc, the
   collected references, and the pieces needed to compile the emitted form."
  [declared-url declared-version extra-atom-entries & {:keys [fi-ref-kw]
                                                       :or {fi-ref-kw ta-kw}}]
  (let [ta-sch (m/schema [:map {:closed true} [:x {:optional true} :string]]
                         fp/fhir-registry-options)
        tb-sch (m/schema [:map {:closed true} [:u {:optional true} :string]]
                         fp/fhir-registry-options)
        tc-sch (m/schema [:map {:closed true} [:u {:optional true} :string]]
                         fp/fhir-registry-options)
        atom-entries (merge {ta-kw {:sch ta-sch} tb-kw {:sch tb-sch} tc-kw {:sch tc-sch}}
                            (when extra-atom-entries
                              (into {} (map (fn [[k v]] [k {:sch ({:ta ta-sch :tb tb-sch} v)}]))
                                    extra-atom-entries)))
        opts (update fp/fhir-registry-options :registry
                     #(mr/composite-registry % (mr/registry {ta-kw ta-sch tb-kw tb-sch tc-kw tc-sch})))
        sub-sch (m/schema [:sequential [:ref ta-kw]] opts)
        field-info (shape/field-info {:code ta-url} "*" fi-ref-kw)
        main-attr {:id "TP.f" :max "1"}
        attr-type {:code declared-url}
        sub-elements [{:path ["TP" "f"] :max "1"}
                      {:path ["TP" "f" "u"] :comment "narrowed"}]
        update-existing (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                                    'update-existing-child-schema)
        run (fn [f]
              (let [refs (atom #{})]
                (binding [fdm/*schema-atom* (atom atom-entries)
                          fdm/*references-atom* refs
                          fdm/*recursive-references* #{}
                          fdm/*base-refs* (atom {})]
                  {:result (f) :references @refs})))
        outer (run #(update-existing {:sch nil :form []} :f attr-type main-attr {}
                                     sub-elements ["TP" "f"] declared-version
                                     sub-sch field-info))
        inner (run #(fdm/attr->value-schema-patch
                     {:sch (fdm/unwrap-sequential sub-sch) :form []
                      :field-info field-info :new-field? false}
                     "TP.f" attr-type main-attr sub-elements ["TP" "f"]
                     declared-version))]
    {:patch-form (first (:form (:result outer)))
     :references (:references outer)
     :sub-acc (:result inner)
     :ta-sch ta-sch
     :tb-sch tb-sch
     :opts opts}))

(defn- compile-patch-form
  "Compile the emitted (mu/update :f ...) step against a parent schema whose :f
   is the inherited [:sequential [:ref TA]], with base-TB resolvable. This is
   the compile the generated .cljc file performs; pre-fix it threw
   :malli.util/no-entry."
  [{:keys [patch-form opts tb-sch]}]
  (let [parent (m/schema [:map [:f [:sequential [:ref ta-kw]]]] opts)
        f (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
            (eval (list 'fn '[options base-TB parent] (list '-> 'parent patch-form))))]
    (f opts (fn [] tb-sch) parent)))

(deftest declared-type-override-test
  (testing "declared type differing from the inherited ref wins the descent"
    (let [{:keys [patch-form references sub-acc] :as scenario}
          (narrowed-choice-scenario tb-url "1.0" nil)]
      (is (:type-override? sub-acc)
          "the descent acc is flagged as a declared-type override")
      (is (some? (mu/get (:sch sub-acc) :u))
          "generation-time :sch walks the declared type (has :u)")
      (is (nil? (mu/get (:sch sub-acc) :x))
          "generation-time :sch is not the inherited type (no :x)")
      (is (tree-contains? patch-form '(base-TB))
          "emitted form threads from the declared type's base fn")
      (let [thread (-> patch-form (nth 2) (nth 2) (nth 3) (nth 2))]
        (is (= '-> (first thread)))
        (is (= '(base-TB) (second thread))
            "the thread starts at (base-TB), not at the inherited schema")
        (is (not (tree-contains? (rest thread) 'inner-sch))
            "the inherited inner-sch is not re-threaded")
        (is (= '(mu/update-properties merge {:max 1}) (last thread))
            "trailing forms are flattened onto the same thread"))
      (is (contains? references tb-kw)
          "the declared type is recorded as a reference")
      (is (not (contains? references ta-kw))
          "the inherited ref is not recorded as a dead dependency")
      (let [compiled (compile-patch-form scenario)
            inner (mu/get (mu/get compiled :f) 0)]
        (is (= :sequential (m/type (mu/get compiled :f)))
            "the sequential wrapper is preserved")
        (is (some? (mu/get inner :u)))
        (is (nil? (mu/get inner :x)))
        (is (= {:closed true :resourceType "TP.f" :max 1}
               (m/properties inner)))
        (is (= {:comment "narrowed"}
               (some (fn [[k p _]] (when (= k :u) (select-keys p [:comment])))
                     (m/children inner)))
            "the constraint on the narrowed type's child landed")))))

(deftest restated-inherited-type-no-override-test
  (testing "restating the inherited type keeps the inherited-schema path byte-identical"
    (let [{:keys [patch-form references sub-acc]}
          (narrowed-choice-scenario ta-url "1.0" nil)]
      (is (not (:type-override? sub-acc)))
      (is (= '(mu/update :f
                         (fn [sch]
                           (mu/update sch 0
                                      (fn [inner-sch]
                                        (-> inner-sch
                                            (m/schema options)
                                            m/deref
                                            (mu/update-entry-properties :u merge {:comment "narrowed"})
                                            (mu/update-properties
                                             (fn [props]
                                               (-> (or props {:closed true})
                                                   (assoc :resourceType "TP.f"))))
                                            (mu/update-properties merge {:max 1}))))))
             patch-form)
          "golden: the pre-fix emitted form is unchanged")
      (is (contains? references ta-kw)
          "the inherited ref is still recorded"))))

(deftest stale-shape-ref-choice-override-test
  (testing "a declared type equal to the shape's stale ref still overrides when
            the compiled entry (what runtime threads from) holds another type"
    ;; On a multi-typed field (CDA ANY) the shape's :ref-kw records the FIRST
    ;; declared variant while the compiled entry holds the LAST. A profile
    ;; restating the first variant (ProblemObservation declares CD; the entry
    ;; holds RTO_PQ_PQ) must still descend into the declared type.
    (let [{:keys [patch-form references sub-acc]}
          (narrowed-choice-scenario tc-url "1.0" nil :fi-ref-kw tc-kw)]
      (is (:type-override? sub-acc))
      (let [thread (-> patch-form (nth 2) (nth 2) (nth 3) (nth 2))]
        (is (= '(base-TC) (second thread))
            "the thread derives from the declared type")
        (is (not (tree-contains? (rest thread) 'inner-sch))
            "no fallback to the compiled entry's arbitrary variant"))
      (is (contains? references tc-kw)))))

(deftest alias-version-restatement-no-override-test
  (testing "a restated type resolving to another version alias is the same type"
    (let [{:keys [patch-form sub-acc]}
          (narrowed-choice-scenario ta-url "2.0" {ta-alias-kw :ta})]
      (is (not (:type-override? sub-acc))
          "alias keywords differ only in version; namespaces match, no override")
      (is (tree-contains? patch-form 'inner-sch)
          "the inherited-schema thread is kept")
      (is (not (tree-contains? patch-form '(base-TA)))
          "no base fn is emitted for the restated alias"))))

(deftest merge-slice-into-multi-base-arm-test
  (testing "the matched arm's schema (not its props) seeds the slice thread"
    (let [msim (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                           'merge-slice-into-multi-form)
          step (msim :f "dv" '(-> base-arm (mu/assoc :y :string)) false)
          parent (m/schema [:map [:f [:multi {:dispatch :t}
                                      ["dv" [:map [:x :string]]]
                                      [:malli.core/default [:map [:d :string]]]]]]
                           fp/fhir-registry-options)
          f (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
              (eval (list 'fn '[options parent] (list '-> 'parent step))))
          merged (f fp/fhir-registry-options parent)
          arm (some (fn [[k _ s]] (when (= k "dv") s))
                    (m/children (mu/get merged :f)))]
      (is (some? (mu/get arm :x))
          "the existing dv arm's fields survive (base-arm was its schema)")
      (is (some? (mu/get arm :y))
          "the slice constraint applied on top"))))

(deftest duplicate-slice-dispatch-dedupe-test
  (testing "content-identical slices sharing a dispatch value emit one arm"
    (let [flush-fn (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                               'flush-pending-slicing)
          base-sch (m/schema [:map [:code :string]] fp/fhir-registry-options)
          acc {:form []
               :pending-slicing
               {:f {:discriminators [{:type "value" :path "code"}]
                    :rules "open"
                    :field-is-sequential? true
                    :base-form nil
                    :base-sch base-sch
                    :slices [{:slice-name "s1" :dispatch-value "REFR"
                              :form ['(mu/assoc :a :string)]}
                             {:slice-name "s2" :dispatch-value "REFR"
                              :form ['(mu/assoc :b :string)]}]}}}
          step (first (:form (flush-fn acc)))
          multi (-> step (nth 2) (nth 2) second)
          arms (drop 2 multi)]
      (is (= ["REFR" :malli.core/default] (mapv first arms))
          "one arm per dispatch value plus the open default")
      (is (tree-contains? (first arms) '(mu/assoc :b :string))
          "the last slice wins the collided dispatch value")
      (let [parent (m/schema [:map [:f [:sequential [:map [:code :string]]]]]
                             fp/fhir-registry-options)
            f (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                (eval (list 'fn '[options parent] (list '-> 'parent step))))
            compiled (f fp/fhir-registry-options parent)]
        (is (= :multi (m/type (mu/get (mu/get compiled :f) 0)))
            "the emitted :multi compiles (no :malli.core/duplicate-keys)")))))

(deftest canonical-version-test
  (testing "pinned canonical"
    (is (= "http://hl7.org/fhir/StructureDefinition/alternate-reference"
           (fdm/strip-canonical-version
            "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0")))
    (is (= "5.2.0"
           (fdm/canonical-version
            "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0"))))
  (testing "unpinned canonical is returned whole"
    (is (= "http://hl7.org/fhir/StructureDefinition/Patient"
           (fdm/strip-canonical-version "http://hl7.org/fhir/StructureDefinition/Patient")))
    (is (nil? (fdm/canonical-version "http://hl7.org/fhir/StructureDefinition/Patient")))))

(deftest canonical-index-test
  (let [r4b {:id "hl7.fhir.r4b.core" :version "4.3.0" :dependencies {}
             :plan [{:url "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden" :version "4.3.0"}
                    {:url "http://hl7.org/fhir/StructureDefinition/Patient" :version "4.3.0"}]}
        fx  {:id "hl7.fhir.uv.extensions.r4" :version "5.3.0-ballot-tc1" :dependencies {}
             :plan [{:url "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden" :version "5.3.0-ballot-tc1"}
                    {:url "http://hl7.org/fhir/StructureDefinition/alternate-reference" :version "5.3.0-ballot-tc1"}]}
        index (gen/canonical-index [r4b fx])
        ;; xver declares extensions at 5.2.0 and gets 5.3.0-ballot-tc1 instead
        xver  {:id "hl7.fhir.uv.xver-r5.r4" :version "0.1.0"
               :dependencies {"hl7.fhir.r4.core" "4.0.1"
                              "hl7.fhir.uv.extensions.r4" "5.2.0"}}]

    (testing "a canonical defined by two packages records both, with the publishing package"
      (is (= [{:pkg-id "hl7.fhir.r4b.core" :pkg-version "4.3.0" :version "4.3.0"
               :kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0}
              {:pkg-id "hl7.fhir.uv.extensions.r4" :pkg-version "5.3.0-ballot-tc1"
               :version "5.3.0-ballot-tc1"
               :kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1}]
             (get index "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"))))

    (testing "version ordering infers the scheme when no versionAlgorithm is declared"
      (is (neg? (fdm/compare-canonical-versions "4.3.0" "5.3.0-ballot-tc1")))
      (is (neg? (fdm/compare-canonical-versions "5.3.0-ballot-tc1" "5.3.0"))
          "a pre-release orders below the release it qualifies")
      (is (pos? (fdm/compare-canonical-versions "2.10.0" "2.9.0"))
          "numeric segments compare numerically, not lexically")
      (is (zero? (fdm/compare-canonical-versions "4.3.0" "4.3.0"))))

    (binding [fdm/*canonical-index* index
              fdm/*schema-atom* (atom {:org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 {}
                                       :org.hl7.fhir.StructureDefinition.Patient/v4-3-0 {}})]
      (testing "a pin a package publishes outright wins"
        (binding [fdm/*current-package* xver]
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden|5.3.0-ballot-tc1")))))

      (testing "a pin no resource publishes resolves through the declared dependency"
        ;; Nothing anywhere is version 5.2.0; xver declared extensions at 5.2.0 and
        ;; this run substituted 5.3.0-ballot-tc1 for it.
        (binding [fdm/*current-package* xver]
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden|5.2.0")))))

      (testing "an uninterpretable pin falls back to the latest already generated"
        ;; No resource publishes 9.9.9 and no declared dependency names it, so the
        ;; pin carries no information; preferring something already generated keeps
        ;; the reference inside packages the referrer has seen.
        (binding [fdm/*current-package* {:id "x" :version "1" :dependencies {}}]
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden|9.9.9")))))

      (testing "an unpinned canonical takes the latest already generated, per R4B"
        (binding [fdm/*current-package* xver]
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden")))))

      (testing "a forward reference resolves to the package that will define it"
        (binding [fdm/*current-package* xver]
          (is (= :org.hl7.fhir.StructureDefinition.alternate-reference/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0")))))

      (testing "a canonical no package defines does not resolve"
        (is (nil? (fdm/resolve-canonical-kw
                   "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-timeout")))))

    (testing "without an index bound, resolution is inert"
      (binding [fdm/*canonical-index* nil
                fdm/*schema-atom* (atom {})]
        (is (nil? (fdm/resolve-canonical-kw "http://hl7.org/fhir/StructureDefinition/Patient")))))))

;; ---------------------------------------------------------------------------
;; Slicing dispatch: the emitted :multi dispatch fn must agree with the arm keys
;;
;; These test the PAIR. profile-discriminator-dispatch-value-test above covers
;; only extract-dispatch-value (the arm-key half) and passes even when the
;; dispatch fn computes something no arm can match -- which is how 130 dead
;; profile dispatches shipped.
;; ---------------------------------------------------------------------------

(defn- compiled-multi
  "Run flush-pending-slicing over one pending entry, compile the emitted step
   against `parent`, and return the compiled :multi under key `k` (or nil when
   no :multi was emitted)."
  [pending k parent]
  (let [flush-fn (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                             'flush-pending-slicing)
        step (first (:form (flush-fn {:form [] :pending-slicing pending})))]
    (when step
      (let [f (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                (eval (list 'fn '[options parent] (list '-> 'parent step))))
            compiled (f fp/fhir-registry-options parent)
            inner (mu/get (mu/get compiled k) 0)]
        (when (= :multi (m/type inner)) inner)))))

(defn- multi-dispatch [multi] (:dispatch (m/properties multi)))
(defn- multi-arm-keys [multi] (mapv first (m/children multi)))

(defn- dispatches-to-an-arm?
  "The invariant: applying the dispatch fn to `instance` yields a key that is
   actually one of the arms, and not the open default."
  [multi instance]
  (let [k ((multi-dispatch multi) instance)]
    (and (contains? (set (multi-arm-keys multi)) k)
         (not= :malli.core/default k))))

(deftest value-dispatch-agrees-with-arm-keys-test
  (testing "value discriminators already agree -- this pins the 1489 that work"
    (let [base-sch (m/schema [:map [:code :string]] fp/fhir-registry-options)
          multi (compiled-multi
                 {:f {:discriminators [{:type "value" :path "code"}]
                      :rules "open" :field-is-sequential? true
                      :base-form nil :base-sch base-sch
                      :slices [{:slice-name "s1" :dispatch-value "REFR"
                                :form ['(mu/assoc :a :string)]}]}}
                 :f
                 (m/schema [:map [:f [:sequential [:map [:code :string]]]]]
                           fp/fhir-registry-options))]
      (is (some? multi) "a :multi is emitted for a value discriminator")
      (is (= ["REFR" :malli.core/default] (multi-arm-keys multi)))
      (is (dispatches-to-an-arm? multi {:code "REFR"})
          "dispatch on a matching instance selects the REFR arm"))))

(deftest exists-dispatch-agrees-with-arm-keys-test
  (testing "USRealmHeader ClinicalDocument.informant: provider vs non-provider"
    ;; Two exists discriminators. provider constrains assignedEntity min 1 and
    ;; forbids relatedEntity; non-provider is the inverse. Arm keys must be
    ;; [relatedEntity? assignedEntity?] booleans, and the dispatch must compute
    ;; the same booleans off an instance.
    (let [extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                              'extract-dispatch-value)
          slice-path ["ClinicalDocument" "informant"]
          discs [{:type "exists" :path "relatedEntity"}
                 {:type "exists" :path "assignedEntity"}]
          provider-subs [{:path ["ClinicalDocument" "informant"] :sliceName "provider"}
                         {:path ["ClinicalDocument" "informant" "assignedEntity"] :min 1}
                         {:path ["ClinicalDocument" "informant" "relatedEntity"] :max "0"}]
          non-provider-subs [{:path ["ClinicalDocument" "informant"] :sliceName "non-provider"}
                             {:path ["ClinicalDocument" "informant" "assignedEntity"] :max "0"}
                             {:path ["ClinicalDocument" "informant" "relatedEntity"] :min 1}]
          provider-dv (:dispatch-value (extract discs provider-subs slice-path "provider"))
          non-provider-dv (:dispatch-value (extract discs non-provider-subs slice-path "non-provider"))]
      (is (= [false true] provider-dv)
          "provider: relatedEntity absent, assignedEntity present")
      (is (= [true false] non-provider-dv)
          "non-provider: the inverse")
      (let [base-sch (m/schema [:map
                                [:assignedEntity {:optional true} [:map [:id {:optional true} :string]]]
                                [:relatedEntity {:optional true} [:map [:id {:optional true} :string]]]]
                               fp/fhir-registry-options)
            multi (compiled-multi
                   {:informant {:discriminators discs
                                :rules "open" :field-is-sequential? true
                                :base-form nil :base-sch base-sch
                                :slices [{:slice-name "provider" :dispatch-value provider-dv
                                          :form ['(mu/assoc :a :string)]}
                                         {:slice-name "non-provider" :dispatch-value non-provider-dv
                                          :form ['(mu/assoc :b :string)]}]}}
                   :informant
                   (m/schema [:map [:informant [:sequential
                                                [:map
                                                 [:assignedEntity {:optional true} [:map]]
                                                 [:relatedEntity {:optional true} [:map]]]]]]
                             fp/fhir-registry-options))]
        (is (some? multi) "a :multi is emitted for exists discriminators")
        (is (= [false true] ((multi-dispatch multi) {:assignedEntity {:id "x"}}))
            "an instance with only assignedEntity dispatches to the provider key")
        (is (dispatches-to-an-arm? multi {:assignedEntity {:id "x"}}))
        (is (dispatches-to-an-arm? multi {:relatedEntity {:id "x"}}))))))

(deftest profile-dispatch-requires-a-driver-hook-test
  (testing "unbound hook declines: no :multi rather than one that cannot match"
    ;; FHIR defines profile discrimination as validating against each candidate
    ;; profile. How a profile is recognised in an instance is IG/serialization
    ;; specific (CDA templateId, FHIR meta.profile), so the generator refuses to
    ;; guess. Emitting (fn [m] (get-in m [:section])) against profile-URL arm
    ;; keys is what this replaces.
    (let [base-sch (m/schema [:map [:section [:map [:title {:optional true} :string]]]]
                             fp/fhir-registry-options)
          parent (m/schema [:map [:component [:sequential [:map [:section [:map]]]]]]
                           fp/fhir-registry-options)
          pending {:component
                   {:discriminators [{:type "profile" :path "section"}]
                    :rules "open" :field-is-sequential? true
                    :base-form nil :base-sch base-sch
                    :slices [{:slice-name "allergies"
                              :dispatch-value "http://hl7.org/cda/us/ccda/StructureDefinition/AllergiesAndIntolerancesSection"
                              :form ['(mu/assoc :a :string)]}]}}]
      (is (nil? (compiled-multi pending :component parent))
          "no hook bound -> no :multi emitted")))

  (testing "a bound hook supplies the dispatch form and its arms come alive"
    (let [dispatch-var (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                                   '*discriminator-dispatch-fn*)
          url "http://hl7.org/cda/us/ccda/StructureDefinition/AllergiesAndIntolerancesSection"
          base-sch (m/schema [:map [:section [:map [:templateId {:optional true}
                                                    [:sequential [:map
                                                                  [:root {:optional true} :string]
                                                                  [:extension {:optional true} :string]]]]]]]
                             fp/fhir-registry-options)
          parent (m/schema [:map [:component [:sequential [:map [:section [:map]]]]]]
                           fp/fhir-registry-options)
          pending {:component
                   {:discriminators [{:type "profile" :path "section"}]
                    :rules "open" :field-is-sequential? true
                    :base-form nil :base-sch base-sch
                    :slices [{:slice-name "allergies" :dispatch-value url
                              :form ['(mu/assoc :a :string)]}]}}
          ;; a stand-in for the CDA driver's templateId resolver
          hook (fn [{:keys [type path arms]}]
                 (when (= "profile" type)
                   (let [pairs (mapv (fn [a] [["2.16.840.1.113883.10.20.22.2.6.1" "2015-08-01"]
                                              (:dispatch-value a)])
                                     arms)]
                     `(~'fn [~'m]
                       (let [~'tid (~'get-in ~'m ~(vec (concat path [:templateId])))
                             ~'ks (~'into #{} (~'map (~'juxt :root :extension)) ~'tid)]
                         (~'some (~'fn [[~'k ~'u]] (~'when (~'contains? ~'ks ~'k) ~'u))
                                 ~pairs))))))
          instance {:section {:templateId [{:root "2.16.840.1.113883.10.20.22.2.6.1"
                                            :extension "2015-08-01"}]}}]
      (with-bindings {dispatch-var hook}
        (let [multi (compiled-multi pending :component parent)]
          (is (some? multi) "hook bound -> :multi emitted")
          (is (= url ((multi-dispatch multi) instance))
              "the dispatch resolves the instance's templateId to the arm's profile URL")
          (is (dispatches-to-an-arm? multi instance))))

      (testing "a hook that declines is the same as no hook"
        (with-bindings {dispatch-var (constantly nil)}
          (is (nil? (compiled-multi pending :component parent))))))))

;; ---------------------------------------------------------------------------
;; type.profile on a non-slice child
;; ---------------------------------------------------------------------------

(deftest child-type-profile-narrowing-test
  (let [pkg   {:id "test.pkg" :version "1.0.0" :dependencies {}}
        index (gen/canonical-index
               [{:id "test.pkg" :version "1.0.0" :dependencies {}
                 :plan [{:url "http://example.org/StructureDefinition/Narrowed" :version "1.0.0"}]}])
        narrowed-kw :org.example.StructureDefinition.Narrowed/v1-0-0
        base-kw     :org.hl7.fhir.StructureDefinition.Patient/v4-3-0
        ;; prim-or-ref is reached only for a declared type with no deeper
        ;; constrained children -- exactly the population this narrows.
        run   (fn [attr-type]
                (let [refs (atom #{})]
                  (binding [fdm/*references-atom* refs]
                    [(:form (fdm/prim-or-ref {} attr-type "4.3.0")) @refs])))]
    (binding [fdm/*canonical-index*   index
              fdm/*current-package*   pkg
              fdm/*known-canonical-kws* #{narrowed-kw}
              fdm/*schema-atom*       (atom {base-kw {}})]

      (testing "gated off, a resolvable profile changes nothing"
        (is (= [[[:ref base-kw]] #{base-kw}]
               (run {:code "Patient"
                     :profile ["http://example.org/StructureDefinition/Narrowed"]}))))

      (binding [fdm/*honor-child-type-profile* true]
        (testing "one resolvable profile refs the profile, not the declared type"
          (is (= [[[:ref narrowed-kw]] #{narrowed-kw}]
                 (run {:code "Patient"
                       :profile ["http://example.org/StructureDefinition/Narrowed"]})))
          (is (= [[[:ref base-kw]] #{base-kw}]
                 (run {:code "Patient"}))
              "and no profile still refs the declared type"))

        (testing "several profiles keep the base type -- type.profile is any-of"
          (is (= [[[:ref base-kw]] #{base-kw}]
                 (run {:code "Patient"
                       :profile ["http://example.org/StructureDefinition/Narrowed"
                                 "http://example.org/StructureDefinition/Other"]}))))

        (testing "a primitive declared type is never narrowed"
          ;; CDA puts value-domain profiles (cs-simple, bl-simple) on code and
          ;; boolean children; narrowing there would rewrite the core datatypes.
          (is (= [[:string {:fhir/primitive "code"}]]
                 (first (run {:code "code"
                              :profile ["http://example.org/StructureDefinition/Narrowed"]})))))

        (testing "a canonical no package defines degrades to the base type and reports"
          (let [unresolved (atom [])]
            (binding [fdm/*unresolved-profiles* unresolved
                      fdm/*current-definition* "http://example.org/StructureDefinition/Referrer"]
              (is (= [[[:ref base-kw]] #{base-kw}]
                     (run {:code "Patient"
                           :profile ["http://example.org/StructureDefinition/Missing"]})))
              (is (= [{:profile "http://example.org/StructureDefinition/Missing"
                       :from "http://example.org/StructureDefinition/Referrer"
                       :degraded-to base-kw}]
                     @unresolved)))))))))

;; ---------------------------------------------------------------------------
;; XML wire facts the schemas have to carry
;; ---------------------------------------------------------------------------

(deftest representation-props-xml-name-test
  (testing "xml-name and xml-namespace decouple the wire name from the key"
    ;; CDA's sdtc extensions are the only thing that says Observation.sdtcCategory
    ;; writes as sdtc:category; without these the element writes under its key.
    (is (= {:xml/name "category"
            :xml/namespace "urn:hl7-org:sdtc"}
           (fdm/representation-props
            {:extension [{:url "http://hl7.org/fhir/tools/StructureDefinition/xml-namespace"
                          :valueUri "urn:hl7-org:sdtc"}
                         {:url "http://hl7.org/fhir/tools/StructureDefinition/xml-name"
                          :valueString "category"}]})))))

(def ^:private build-sd-properties
  (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'build-sd-properties))

(deftest element-order-for-logical-models-test
  (let [order [:realmCode :typeId :templateId :classCode]]
    (testing "a resource names its root path in :type"
      (binding [fdm/*element-order* {"Patient" order}]
        (is (= order (:fhir/element-order
                      (build-sd-properties
                       {:type "Patient" :snapshot {:element [{:path "Patient"}]}}
                       nil))))))

    (testing "a logical model sets :type to the canonical URL, and the order table is keyed by the snapshot root"
      ;; Looking the URL up finds nothing, which is how every CDA class shipped
      ;; with no element order at all while FHIR XML requires one.
      (binding [fdm/*element-order* {"Observation" order}]
        (is (= order (:fhir/element-order
                      (build-sd-properties
                       {:type "http://hl7.org/cda/stds/core/StructureDefinition/Observation"
                        :snapshot {:element [{:path "Observation"} {:path "Observation.realmCode"}]}}
                       nil))))))

    (testing "no snapshot is not an error"
      (binding [fdm/*element-order* {"Observation" order}]
        (is (nil? (:fhir/element-order
                   (build-sd-properties {:type "http://example.org/X"} nil))))))))

(deftest fixed-value-keeps-its-wire-position-test
  (testing "a fixed attribute is still an attribute, and still optional"
    ;; A fixed value pins the CONTENT. Dropping the entry properties with it
    ;; left Section.classCode and ST.mediaType looking like required child
    ;; elements.
    (let [main-attr {:id "TP.classCode" :min 0 :max "1"
                     :representation ["xmlAttr"] :fixedCode "DOCSECT"}
          acc (binding [fdm/*schema-atom* (atom {})
                        fdm/*references-atom* (atom #{})
                        fdm/*recursive-references* #{}
                        fdm/*base-refs* (atom {})]
                (fdm/apply-element-patch
                 {:sch (m/schema [:map {:closed true}] fp/fhir-registry-options) :shape {} :form []}
                 "TP.classCode" :classCode {:code "code"} main-attr {} ["TP" "classCode"] "1.0"))]
      (is (tree-contains? (:form acc) [:enum {} "DOCSECT"])
          "the fixed value still pins the content")
      (is (tree-contains? (:form acc) {:xml/attr true
                                       :fhir/representation ["xmlAttr"]
                                       :optional true})
          "and the wire position survives with it"))))
