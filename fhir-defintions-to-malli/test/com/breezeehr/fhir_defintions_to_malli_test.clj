(ns com.breezeehr.fhir-defintions-to-malli-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [com.breezeehr.fhir-shape :as shape]
            [com.breezeehr.fhir-schema-gen :as gen]
            [com.breezeehr.fhir-primitives :as fp]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
            [clojure.string :as str]))

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
                          fdm/*referencing-definition* {:version declared-version}
                          fdm/*references-atom* refs
                          fdm/*recursive-references* #{}
                          fdm/*base-refs* (atom {})]
                  {:result (f) :references @refs})))
        outer (run #(update-existing {:sch nil :form []} :f attr-type main-attr {}
                                     sub-elements ["TP" "f"] sub-sch field-info))
        inner (run #(fdm/attr->value-schema-patch
                     {:sch (fdm/unwrap-sequential sub-sch) :form []
                      :field-info field-info :new-field? false}
                     "TP.f" attr-type main-attr sub-elements ["TP" "f"]))]
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

    (binding [fdm/*canonical-index* index]
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

      (testing "a forward reference resolves to the package that will define it"
        (binding [fdm/*current-package* xver]
          (is (= :org.hl7.fhir.StructureDefinition.alternate-reference/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0")))))

      (testing "a canonical no package defines does not resolve"
        (is (nil? (fdm/resolve-canonical-kw
                   "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-timeout"))))

      (testing "with no closure, two definitions of one canonical are ambiguous, never newest-wins"
        (binding [fdm/*current-package* {:id "x" :version "1" :dependencies {}}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot resolve canonical"
                                (fdm/resolve-canonical-kw
                                 "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden|9.9.9"))
              "a pin nothing publishes and no dependency names carries no information"))))

    (testing "without an index bound, resolution is inert"
      (binding [fdm/*canonical-index* nil]
        (is (nil? (fdm/resolve-canonical-kw "http://hl7.org/fhir/StructureDefinition/Patient")))))))

(deftest resolve-in-package-closure-test
  (let [hidden "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"
        alt    "http://hl7.org/fhir/StructureDefinition/alternate-reference"
        packages (gen/prepare-packages
                  [{:id "hl7.fhir.r4b.core" :version "4.3.0" :dependencies {}
                    :substitutes ["hl7.fhir.r4.core"]
                    :plan [{:url hidden :version "4.3.0"}]}
                   {:id "hl7.fhir.uv.extensions.r4" :version "5.3.0-ballot-tc1"
                    :dependencies {"hl7.fhir.r4.core" "4.0.1" "hl7.terminology.r4" "6.5.0"}
                    :plan [{:url hidden :version "5.3.0-ballot-tc1"}
                           {:url alt :version "5.3.0-ballot-tc1"}]}
                   {:id "hl7.fhir.us.core" :version "8.0.1"
                    :dependencies {"hl7.fhir.r4.core" "4.0.1"
                                   "hl7.fhir.uv.extensions.r4" "5.2.0"}
                    :plan []}
                   {:id "r4-only.ig" :version "1.0.0"
                    :dependencies {"hl7.fhir.r4.core" "4.0.1"}
                    :plan []}])
        [r4b fx uscore r4-only] packages
        index    (gen/canonical-index packages)
        resolve  #(fdm/resolve-in-package index %1 %2)]

    (testing "closures follow declared dependencies through substitutes, ignoring ones the run lacks"
      (is (= #{"hl7.fhir.r4b.core"} (:closure r4b)))
      (is (= #{"hl7.fhir.r4b.core" "hl7.fhir.uv.extensions.r4"} (:closure fx)))
      (is (= #{"hl7.fhir.r4b.core" "hl7.fhir.uv.extensions.r4" "hl7.fhir.us.core"}
             (:closure uscore)))
      (is (= {"hl7.fhir.r4.core" "hl7.fhir.r4b.core"} (:dependency-run-ids fx))))

    (testing "a package that does not depend on the other definer sees only its own copy"
      (is (= {:kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 :by :closure}
             (resolve r4b hidden)))
      (is (= {:kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 :by :closure}
             (resolve r4-only hidden))))

    (testing "two definers in the closure are ambiguous without an override"
      (is (= {:ambiguous [{:pkg-id "hl7.fhir.r4b.core" :version "4.3.0"}
                          {:pkg-id "hl7.fhir.uv.extensions.r4" :version "5.3.0-ballot-tc1"}]}
             (resolve uscore hidden))))

    (testing "an override settles the ambiguity, and must name a candidate the referrer sees"
      (is (= {:kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1
              :by :override}
             (resolve (assoc-in uscore [:overrides hidden] "5.3.0-ballot-tc1") hidden)))
      (is (= "9.9.9"
             (:bad-override (resolve (assoc-in uscore [:overrides hidden] "9.9.9") hidden))))
      (is (= {:kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 :by :pin}
             (resolve (assoc-in uscore [:overrides hidden] "5.3.0-ballot-tc1")
                      (str hidden "|4.3.0")))
          "a pin outranks the override; the override only settles what the closure cannot"))

    (testing "a pin naming a declared dependency resolves to the run's substitute"
      (is (= {:kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 :by :pin-dependency}
             (resolve uscore (str hidden "|4.0.1")))
          "hl7.fhir.r4.core 4.0.1 is what R4B stands in for"))

    (testing "a canonical only packages outside the closure define is not borrowed"
      (is (= {:outside-closure [{:pkg-id "hl7.fhir.uv.extensions.r4" :version "5.3.0-ballot-tc1"}]}
             (resolve r4-only alt))))

    (testing "check-references reports by class, and the literal Element is always resolved"
      (let [sd     {:url "http://example.org/StructureDefinition/p" :version "1.0.0"
                    :baseDefinition "http://hl7.org/fhir/StructureDefinition/Element"
                    :differential {:element [{:id "p.a" :type [{:code "string"}
                                                               {:code "Extension" :profile [hidden]}]}
                                             {:id "p.b" :type [{:code "Missing"}]}]}}
            report (gen/check-references [(assoc uscore :plan [sd])] index)]
        (is (= [hidden] (map :canonical (:ambiguous report))))
        (is (= #{"http://example.org/StructureDefinition/p"} (:from (first (:ambiguous report)))))
        (is (= ["http://hl7.org/fhir/StructureDefinition/Element"
                "http://hl7.org/fhir/StructureDefinition/Extension"
                "http://hl7.org/fhir/StructureDefinition/Missing"]
               (map :canonical (:missing report)))
            "nothing in this toy run defines Element or Extension; string is a primitive")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"1 ambiguous"
                              (with-out-str
                                (gen/check-references! [(assoc uscore :plan [sd])] index))))))))

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

(deftest wildcard-exists-dispatch-test
  (let [extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli
                            'extract-dispatch-value)
        discs [{:type "exists" :path "*"}]
        slice (fn [slice-name & children]
                (let [slice-path ["Section" (str "entry:" slice-name)]
                      subs (into [{:path slice-path :sliceName slice-name}]
                                 (map (fn [c] {:path (conj slice-path c) :min 1}))
                                 children)]
                  (merge (extract discs subs slice-path slice-name)
                         {:slice-name slice-name
                          :sub-elements subs
                          :slice-path slice-path
                          :form [(list 'mu/assoc (keyword slice-name) :string)]})))
        entry-sch [:map
                   [:typeCode {:optional true} :string]
                   [:act {:optional true} [:map]]
                   [:observation {:optional true} [:map]]]
        compile (fn [slices]
                  (compiled-multi
                   {:entry {:discriminators discs
                            :rules "open" :field-is-sequential? true
                            :base-form nil
                            :base-sch (m/schema entry-sch fp/fhir-registry-options)
                            :slices slices}}
                   :entry
                   (m/schema [:map [:entry [:sequential entry-sch]]] fp/fhir-registry-options)))]
    (testing "C-CDA R2.1 ProblemSection Section.entry: the slice is the child present"
      (let [multi (compile [(slice "act" "act") (slice "observation" "observation")])]
        (is (some? multi) "a :multi is emitted for exists on *")
        (is (= [[true false] [false true] :malli.core/default] (multi-arm-keys multi))
            "arm keys are [act? observation?]")
        (is (= [true false] ((multi-dispatch multi) {:typeCode "DRIV" :act {}}))
            "an entry holding an act dispatches to the act slice")
        (is (= [false true] ((multi-dispatch multi) {:observation {}}))
            "an entry holding an observation dispatches to the observation slice")
        (is (not (dispatches-to-an-arm? multi {:typeCode "DRIV"}))
            "an entry holding neither falls to the open default")))
    (testing "a child every slice requires distinguishes none and is left out"
      (let [multi (compile [(slice "act" "typeCode" "act")
                            (slice "observation" "typeCode" "observation")])]
        (is (= [[true false] [false true] :malli.core/default] (multi-arm-keys multi)))
        (is (dispatches-to-an-arm? multi {:act {}})
            "an act entry missing the shared typeCode still reaches the act arm")))))

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
                  (binding [fdm/*references-atom* refs
                            fdm/*referencing-definition* {:version "4.3.0"}]
                    [(:form (fdm/prim-or-ref {} attr-type)) @refs])))]
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

        (testing "an optional slice whose profile raises a child requirement keeps the base type unless the slice-root switch is on"
          (let [narrowed "http://example.org/StructureDefinition/Narrowed"
                at (fn [path] (let [refs (atom #{})]
                                (binding [fdm/*references-atom* refs
                                          fdm/*referencing-definition* {:version "4.3.0"}
                                          fdm/*profiles-raising-child-requirement* #{narrowed}]
                                  (:form (fdm/prim-or-ref {} {:code "Patient" :profile [narrowed]}
                                                          {:min 0 :path path})))))]
            (is (false? fdm/*honor-slice-root-profile*))
            (is (= [[:ref base-kw]] (at ["Observation" "participant:facility-location"])))
            (binding [fdm/*honor-slice-root-profile* true]
              (is (= [[:ref narrowed-kw]] (at ["Observation" "participant:facility-location"]))
                  "the slice root states the profile, so the narrowing is kept")
              (is (= [[:ref base-kw]] (at ["Observation" "participant"]))
                  "an unsliced optional child still keeps the base type"))))

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
                        fdm/*referencing-definition* {:version "1.0"}
                        fdm/*references-atom* (atom #{})
                        fdm/*recursive-references* #{}
                        fdm/*base-refs* (atom {})]
                (fdm/apply-element-patch
                 {:sch (m/schema [:map {:closed true}] fp/fhir-registry-options) :shape {} :form []}
                 "TP.classCode" :classCode {:code "code"} main-attr {} ["TP" "classCode"]))]
      (is (tree-contains? (:form acc) [:enum {} "DOCSECT"])
          "the fixed value still pins the content")
      (is (tree-contains? (:form acc) {:xml/attr true
                                       :fhir/representation ["xmlAttr"]
                                       :optional true})
          "and the wire position survives with it"))))

;; ---------------------------------------------------------------------------
;; Polymorphic slots discriminated by xsi:type
;;
;; An element with no `[x]` that declares several types is one slot, not
;; several fields. Folding the type list patched the same key once per type and
;; left whichever the package listed last.
;; ---------------------------------------------------------------------------

(defn- union-scenario
  "Drive element-definition->attribute for an element `TP.f` declaring
   `type-urls` in that order, with cardinality `max-val`. Returns the emitted
   :form, the collected references and the resulting shape."
  [type-urls max-val]
  (let [opts (update fp/fhir-registry-options :registry
                     #(mr/composite-registry
                       %
                       (mr/registry {ta-kw (m/schema [:map [:x :string]] fp/fhir-registry-options)
                                     tb-kw (m/schema [:map [:u :string]] fp/fhir-registry-options)
                                     tc-kw (m/schema [:map [:w :string]] fp/fhir-registry-options)})))
        refs (atom #{})
        items [{:path ["TP" "f"] :min 0 :max max-val
                :id "TP.f"
                :type (mapv (fn [u] {:code u}) type-urls)}]
        acc (binding [fdm/*schema-atom* (atom {})
                      fdm/*referencing-definition* {:version "1.0"}
                      fdm/*references-atom* refs
                      fdm/*recursive-references* #{}
                      fdm/*base-refs* (atom {})]
              (fdm/element-definition->attribute
               {:sch (m/schema [:map {:closed true}] opts) :shape {} :form []}
               ["TP"] items))]
    {:form (:form acc) :references @refs :shape (:shape acc)}))

(defn- union-value-form
  "The value form the emitted (mu/assoc :f <value>) carries."
  [form]
  (some (fn [f] (when (and (seq? f) (= 'mu/assoc (first f)) (= :f (second f)))
                  (nth f 2)))
        form))

(deftest polymorphic-slot-becomes-one-xsi-type-multi-test
  (testing "several declared types on a non-[x] element become ONE key holding an
            ordered :multi, in declaration order, with the first declared type
            repeated as the ::m/default arm"
    (let [{:keys [form references shape]} (union-scenario [ta-url tb-url tc-url] "*")
          value (union-value-form form)]
      (is (= 1 (count (filter (fn [f] (and (seq? f) (= 'mu/assoc (first f)) (= :f (second f))))
                              form)))
          "one key, emitted once - not once per declared type")
      (is (= :sequential (first value))
          "the cardinality wrapper stays outermost, so the slot still repeats")
      (let [multi (second value)]
        (is (= :multi (first multi)))
        (is (true? (:xml/type-union (second multi)))
            "marked as a type union, so a slice union is still told apart from it")
        (is (= ["TA" "TB" "TC" :malli.core/default]
               (mapv first (drop 2 multi)))
            "arm per declared type, in declaration order, then the default arm")
        (is (= (nth (nth multi 2) 1) (nth (last multi) 1))
            "the default arm repeats the FIRST declared type, which is what an
             instance stating no xsi:type is an instance of"))
      (is (= #{ta-kw tb-kw tc-kw} (into #{} (filter #{ta-kw tb-kw tc-kw}) references))
          "every declared type is recorded as a reference, as before")
      (is (true? (shape/multi-typed? (shape/get-field shape :f))))
      (is (= [ta-url tb-url tc-url] (:type-codes (shape/get-field shape :f))))
      (is (= ta-kw (:ref-kw (shape/get-field shape :f)))
          "the shape's ref-kw names the default arm, so a later differential
           restating the element with no type code still resolves it"))))

(deftest polymorphic-slot-arm-names-are-wire-names-test
  (testing "an arm is keyed by the name an instance writes in xsi:type, which
            spells an interval IVL_TS where the StructureDefinition id spells
            it IVL-TS"
    (let [ivl "http://hl7.org/cda/stds/core/StructureDefinition/IVL-TS"
          sxpr "http://hl7.org/cda/stds/core/StructureDefinition/SXPR-TS"
          {:keys [form]} (union-scenario [ivl sxpr] "*")
          multi (second (union-value-form form))]
      (is (= ["IVL_TS" "SXPR_TS" :malli.core/default] (mapv first (drop 2 multi)))))))

(deftest polymorphic-slot-dispatch-form-is-printable-and-reads-the-claim-test
  (testing "the dispatch survives being printed to a file and read back: it is a
            literal fn form naming nothing outside clojure.core, it strips a
            namespace prefix the way a type resolver does, and it dispatches nil
            for a value stating no type - which is what makes the default arm
            reachable"
    (let [{:keys [form]} (union-scenario [ta-url tb-url] "*")
          multi (second (union-value-form form))
          dispatch-form (:dispatch (second multi))
          f (eval (read-string (pr-str dispatch-form)))]
      (is (= 'fn (first dispatch-form)) "a form, never a closure")
      (is (= "IVL_TS" (f {:xsi/type "IVL_TS"})))
      (is (= "IVL_TS" (f {:xsi/type "hl7:IVL_TS"})) "a prefixed claim reaches its arm")
      (is (nil? (f {})) "no claim dispatches to the default arm")
      (is (nil? (f {:xsi/type 7})) "a non-string claim is not a name"))))

(deftest single-typed-element-is-unchanged-test
  (testing "one declared type emits exactly what it emitted before - the choice
            only exists where the element declares a choice"
    (let [{:keys [form shape]} (union-scenario [ta-url] "*")]
      (is (= [:sequential [:ref ta-kw]] (union-value-form form)))
      (is (not (shape/multi-typed? (shape/get-field shape :f))))
      (is (nil? (:type-codes (shape/get-field shape :f)))))))

(deftest polymorphic-slot-at-max-one-has-no-sequential-test
  (testing "cardinality is decided where it always was, so a 0..1 slot is the
            multi alone"
    (let [value (union-value-form (:form (union-scenario [ta-url tb-url] "1")))]
      (is (= :multi (first value))))))

(deftest choice-element-still-expands-per-type-test
  (testing "an element WITH [x] is FHIR's choice and still gets one key per
            declared type - this change is about the other shape"
    (let [opts fp/fhir-registry-options
          refs (atom #{})
          items [{:path ["TP" "value[x]"] :min 0 :max "1" :id "TP.value[x]"
                  :type [{:code "string"} {:code "boolean"}]}]
          acc (binding [fdm/*schema-atom* (atom {})
                        fdm/*referencing-definition* {:version "1.0"}
                        fdm/*references-atom* refs
                        fdm/*recursive-references* #{}
                        fdm/*base-refs* (atom {})]
                (fdm/element-definition->attribute
                 {:sch (m/schema [:map {:closed true}] opts) :shape {} :form []}
                 ["TP"] items))
          keys-assoced (into [] (comp (filter #(and (seq? %) (= 'mu/assoc (first %))))
                                      (map second))
                             (:form acc))]
      (is (= [:valueString :valueBoolean] (filterv #{:valueString :valueBoolean} keys-assoced))
          "one key per type, named after it"))))

(defn- primitive-scenario
  "The form emitted for a code element `TP.f` of cardinality `max`."
  [max]
  (binding [fdm/*schema-atom* (atom {})
            fdm/*referencing-definition* {:version "1.0"}
            fdm/*references-atom* (atom #{})
            fdm/*recursive-references* #{}
            fdm/*base-refs* (atom {})]
    (:form (fdm/element-definition->attribute
            {:sch (m/schema [:map {:closed true}] fp/fhir-registry-options) :shape {} :form []}
            ["TP"]
            [{:path ["TP" "f"] :min 0 :max max :id "TP.f" :type [{:code "code"}]}]))))

(defn- assoced-form [form k]
  (some (fn [f] (when (and (seq? f) (= 'mu/assoc (first f)) (= k (second f)))
                  (nth f 2)))
        form))

(deftest repeating-primitive-companion-admits-nil-test
  (testing "a repeating primitive's value array and its _ companion are parallel:
            each admits nil, so [\"MORN\" nil \"HS\"] can travel with
            [nil {:extension [...]} nil]"
    (let [form (primitive-scenario "*")
          value (assoced-form form :f)
          companion (assoced-form form :_f)]
      (is (= :sequential (first value)))
      (is (= :maybe (first (second value))))
      (is (= :sequential (first companion)))
      (is (= :maybe (first (second companion))) "the companion admits a null slot")
      (is (= :ref (first (second (second companion)))))))
  (testing "a single-valued primitive's companion stays a bare Element ref"
    (is (= :ref (first (assoced-form (primitive-scenario "1") :_f))))))

(defn- nested-min-scenario
  "The form emitted for a profile element `TP.f` of minimum `min` against a
  schema whose `:f` already exists but which the shape does not list, as every
  element below a definition's top level is."
  [min]
  (binding [fdm/*schema-atom* (atom {})
            fdm/*referencing-definition* {:version "1.0"}
            fdm/*references-atom* (atom #{})
            fdm/*recursive-references* #{}
            fdm/*base-refs* (atom {})]
    (:form (fdm/element-definition->attribute
            {:sch (m/schema [:map {:closed true} [:f {:optional true} :string]] fp/fhir-registry-options)
             :shape {}
             :form []}
            ["TP"]
            [{:path ["TP" "f"] :min min :id "TP.f"}]))))

(deftest nested-min-makes-an-existing-child-required-test
  (testing "min 1 on a child the shape does not list (AssignedEntity.addr 1..* under a
            profile's performer) makes the key required"
    (let [form (nested-min-scenario 1)
          patch (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                  (eval (list 'fn '[sch] (list* '-> 'sch form))))
          sch (patch (m/schema [:map {:closed true} [:f {:optional true} :string]] fp/fhir-registry-options))]
      (is (tree-contains? form {:optional false}))
      (is (not (m/validate sch {})) "a map without :f fails")
      (is (m/validate sch {:f "x"}))))
  (testing "min 0 leaves it optional"
    (is (not (tree-contains? (nested-min-scenario 0) {:optional false})))))

(defn- max-on-sequence-scenario
  "`field-sch`, the schema of an existing repeating `:f`, patched by a profile
  element `TP.f` of maximum `max`, with `*max-on-sequence*` bound to `on?`."
  [on? max field-sch]
  (let [base (m/schema [:map {:closed true} [:f {:optional true} field-sch]] fp/fhir-registry-options)
        form (binding [fdm/*schema-atom* (atom {})
                       fdm/*referencing-definition* {:version "1.0"}
                       fdm/*references-atom* (atom #{})
                       fdm/*recursive-references* #{}
                       fdm/*base-refs* (atom {})
                       fdm/*max-on-sequence* on?]
               (:form (fdm/element-definition->attribute
                       {:sch base :shape {} :form []}
                       ["TP"]
                       [{:path ["TP" "f"] :max max :id "TP.f"}])))
        patch (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                (eval (list 'fn '[sch] (list* '-> 'sch form))))]
    (patch base)))

(deftest max-on-sequence-test
  (testing "max 1 on a field that repeats bounds the :sequential"
    (let [sch (max-on-sequence-scenario true "1" [:sequential :string])]
      (is (m/validate sch {:f ["a"]}))
      (is (not (m/validate sch {:f ["a" "b"]})) "a second occurrence fails")))
  (testing "a numeric max above 1 bounds it too"
    (let [sch (max-on-sequence-scenario true "2" [:sequential :string])]
      (is (m/validate sch {:f ["a" "b"]}))
      (is (not (m/validate sch {:f ["a" "b" "c"]})))))
  (testing "a counted field is bounded on its :sequential, child 0 of the :and"
    (let [sch (max-on-sequence-scenario true "1" [:and {:fhir/slice-counts {}} [:sequential :string] [:fn (constantly true)]])]
      (is (m/validate sch {:f ["a"]}))
      (is (not (m/validate sch {:f ["a" "b"]})))
      (is (= {:max 1} (m/properties (mu/get (mu/get-in sch [:f]) 0))))))
  (testing "max * adds no bound"
    (is (m/validate (max-on-sequence-scenario true "*" [:sequential :string]) {:f ["a" "b"]})))
  (testing "off by default, the bound is not on the :sequential and a second occurrence passes"
    (is (false? fdm/*max-on-sequence*))
    (is (m/validate (max-on-sequence-scenario false "1" [:sequential :string]) {:f ["a" "b"]}))))

(defn- primitive-pattern-scenario
  "The schema of `:f`, a string, patched by a profile element `TP.f` carrying
  `pattern` (an ElementDefinition fragment), with `*primitive-pattern-as-fixed*`
  bound to `on?`."
  [on? pattern]
  (let [base (m/schema [:map {:closed true} [:f {:optional true} :string]] fp/fhir-registry-options)
        form (binding [fdm/*schema-atom* (atom {})
                       fdm/*referencing-definition* {:version "1.0"}
                       fdm/*references-atom* (atom #{})
                       fdm/*recursive-references* #{}
                       fdm/*base-refs* (atom {})
                       fdm/*primitive-pattern-as-fixed* on?]
               (:form (fdm/element-definition->attribute
                       {:sch base :shape {} :form []}
                       ["TP"]
                       [(merge {:path ["TP" "f"] :id "TP.f"} pattern)])))
        patch (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                (eval (list 'fn '[sch] (list* '-> 'sch form))))]
    (patch base)))

(deftest primitive-pattern-as-fixed-test
  (testing "a scalar pattern compiles to an :enum of one, like a fixed value"
    (let [sch (primitive-pattern-scenario true {:patternString "2021-08-01"})]
      (is (m/validate sch {:f "2021-08-01"}))
      (is (not (m/validate sch {:f "2019-12-01"})) "another value fails")
      (is (m/validate sch {}) "the pattern does not make the element required")))
  (testing "a fixed value still wins over a pattern"
    (let [sch (primitive-pattern-scenario true {:fixedString "a" :patternString "b"})]
      (is (m/validate sch {:f "a"}))
      (is (not (m/validate sch {:f "b"})))))
  (testing "off by default, the pattern is left out and any value passes"
    (is (false? fdm/*primitive-pattern-as-fixed*))
    (is (m/validate (primitive-pattern-scenario false {:patternString "2021-08-01"}) {:f "2019-12-01"}))))

;; ---------------------------------------------------------------------------
;; Inherited slicings (doc/inherited-slices-and-slice-counts.md section 3)
;;
;; Synthetic logical models are generated through structure-definition->schema,
;; one after the other, and each emitted form is compiled before the next
;; definition reads it, so a derived profile restates a slice of a slicing that
;; its base really compiled.
;; ---------------------------------------------------------------------------

(def ^:private inh-version "1.0")

(defn- inh-url [n] (str "http://t.example/StructureDefinition/" n))

(defn- inh-kw [n] (fdm/uri->kw2 (inh-url n) inh-version))

(defn- inh-type [n] [{:code (inh-url n)}])

(defn- inh-sd
  "A logical-model StructureDefinition rooted at `n`. Elements are [id attrs]
   pairs; each becomes an ElementDefinition whose path is its id without slice
   names."
  [n base-n elements]
  {:url (inh-url n) :version inh-version :name n :kind "logical" :type n
   :baseDefinition (when base-n (inh-url base-n))
   :differential
   {:element (mapv (fn [[id attrs]]
                     (merge {:id id
                             :path (str/replace id #":[^.]+" "")}
                            attrs))
                   elements)}})

(defn- inh-str [id & {:as attrs}]
  [id (merge {:type [{:code "string"}] :min 0 :max "1"} attrs)])

(defn- inh-profile-hook
  "Stands in for the CDA templateId driver: a section is recognised by its :tid
   string. Declines, as the real hook does, when any arm names a profile it has
   no id for."
  [table]
  (fn [{:keys [type path arms]}]
    (when (= "profile" type)
      (let [entries (reduce (fn [acc {:keys [dispatch-value profiles]}]
                              (let [cands (or (seq profiles) [dispatch-value])
                                    tids (mapv table cands)]
                                (if (every? some? tids)
                                  (conj acc [(set tids) dispatch-value])
                                  (reduced nil))))
                            []
                            arms)]
        (when (seq entries)
          `(~'fn [~'m]
            (~'let [~'tid (~'get-in ~'m ~(conj (vec path) :tid))]
             (~'some (~'fn [[~'tids ~'v]] (~'when (~'contains? ~'tids ~'tid) ~'v))
              ~entries))))))))

(defn- generate-inherited
  "Generate `sds` in order with the rebuild gate `rebuild?` (and the count gate
   when `:counts?`), compiling each emitted form. A nil `rebuild?` binds none of
   the switches, as a caller unaware of them would. Returns {:schema {name compiled-schema} :entry {name entry}
   :options <registry options holding every schema> :uncompiled [...]}."
  [rebuild? hook sds & {:keys [counts?]}]
  (let [element-kw :org.hl7.fhir.StructureDefinition.Element/v1-0
        element-sch (m/schema [:map {:closed false}] fp/fhir-registry-options)
        sa (atom {element-kw {:sch element-sch}})
        uncompiled (atom [])
        compiled (atom {element-kw element-sch})
        options (fn [] (update fp/fhir-registry-options :registry
                               #(mr/composite-registry % (mr/registry @compiled))))
        out (atom {})]
    (with-bindings (cond-> {#'fdm/*schema-atom* sa
                            #'fdm/*honor-child-type-profile* true
                            #'fdm/*discriminator-dispatch-fn* hook}
                     (some? rebuild?) (assoc #'fdm/*rebuild-inherited-slicings* rebuild?
                                             #'fdm/*compile-slice-counts* (boolean counts?)
                                             #'fdm/*uncompiled-slices* uncompiled))
      (doseq [{n :name :as sd} sds]
        ((fdm/structure-definition->schema sa) sd)
        (let [kw (inh-kw n)
              entry (get @sa kw)
              base-fns (into [] (mapcat (fn [[fn-name base-kw]]
                                          [(symbol fn-name) `(~'fn [] (~'get ~'schemas ~base-kw))]))
                             (:base-refs entry))
              f (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                  (eval `(~'fn [~'options ~'schemas]
                          (~'let [~@base-fns] ~(:form entry)))))
              sch (f (options) @compiled)]
          (swap! compiled assoc kw sch)
          (swap! sa assoc-in [kw :sch] sch)
          (swap! out assoc-in [:schema n] sch)
          (swap! out assoc-in [:entry n] entry))))
    (assoc @out :options (options) :uncompiled @uncompiled)))

(defn- inh-multi
  "The :multi at `path` (keys and 0 indexes) of a compiled schema."
  [sch path]
  (reduce (fn [s k] (m/deref (mu/get s k))) sch path))

(defn- inh-arm-keys [multi] (mapv first (m/children multi)))

(defn- inh-errors
  "Explain errors of `instance` as [in-path message-key] pairs."
  [sch options instance]
  (some->> (m/explain sch instance)
           :errors
           (mapv :in)))

;; Section models: Section is the plain type, SecA..SecX profiles over it.
(defn- section-sds []
  [(inh-sd "Section" nil [(inh-str "Section.tid") (inh-str "Section.title") (inh-str "Section.note")])
   (inh-sd "SecA" "Section" [(inh-str "Section.title" :min 1)])
   (inh-sd "SecB" "Section" [(inh-str "Section.note" :min 1)])
   (inh-sd "SecAN" "SecA" [(inh-str "Section.note" :min 1)])
   (inh-sd "SecBN" "SecB" [(inh-str "Section.title" :min 1)])
   (inh-sd "SecX" "SecA" [(inh-str "Section.note" :min 1)])
   (inh-sd "SecR" "Section" [(inh-str "Section.note" :min 1) (inh-str "Section.title" :min 1)])
   (inh-sd "Entry" nil [["Entry.act" {:type (inh-type "Section") :min 0 :max "1"}]])
   (inh-sd "Component" nil [["Component.section" {:type (inh-type "Section") :min 0 :max "1"}]])
   (inh-sd "Body" nil [["Body.component" {:type (inh-type "Component") :min 0 :max "*"}]])])

(def ^:private section-table
  {(inh-url "SecA") "tidA" (inh-url "SecB") "tidB" (inh-url "SecAN") "tidAN"
   (inh-url "SecBN") "tidBN" (inh-url "SecR") "tidR"})

(defn- slice-child
  "A slice `slice` of `path` whose `child` is a Section under `profile`."
  [path slice child profile & [attrs]]
  [[(str path ":" slice) (merge {:sliceName slice :min 1 :max "1"} attrs)]
   [(str path ":" slice "." child) {:type [{:code (inh-url "Section") :profile [(inh-url profile)]}]}]])

(defn- slice-section [path slice profile & [attrs]]
  (slice-child path slice "section" profile attrs))

(defn- nested-base-sds []
  (into (section-sds)
        [(inh-sd "DocBase" nil
                 (into [["DocBase.body" {:type (inh-type "Body") :min 0 :max "1"}]
                        ["DocBase.body.component"
                         {:slicing {:discriminator [{:type "profile" :path "section"}] :rules "open"}}]]
                       (concat (slice-section "DocBase.body.component" "a" "SecA")
                               (slice-section "DocBase.body.component" "b" "SecB"))))]))

(defn- derived-doc
  "DocN restating the slices named in `restates` ([slice profile] pairs) of
   DocBase.body.component."
  [n base restates]
  (inh-sd n base
          (into [["DocBase.body.component" {}]]
                (mapcat (fn [[slice profile]]
                          (slice-section "DocBase.body.component" slice profile)))
                restates)))

(deftest restated-nested-slice-replaces-the-inherited-arm-test
  (let [hook (inh-profile-hook section-table)
        {:keys [schema options uncompiled]}
        (generate-inherited true hook
                            (conj (nested-base-sds)
                                  (derived-doc "DocN" "DocBase" [["a" "SecAN"]])))
        multi-path [:body :component 0]
        base-multi (inh-multi (schema "DocBase") multi-path)
        multi (inh-multi (schema "DocN") multi-path)
        valid? (fn [n instance] (m/validate (schema n) instance options))
        sec (fn [tid & {:as extra}] {:body {:component [{:section (merge {:tid tid} extra)}]}})]
    (testing "the base routes by its own templateId table"
      (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default] (inh-arm-keys base-multi))))
    (testing "the restated arm keeps the inherited key; no dead :section arm is added"
      (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default] (inh-arm-keys multi))))
    (testing "an instance claiming the narrower profile reaches the restated arm"
      (is (= (inh-url "SecA") ((:dispatch (m/properties multi)) {:section {:tid "tidAN"}})))
      (is (nil? ((:dispatch (m/properties multi)) {:section {:tid "tidA"}}))
          "the base-only templateId no longer reaches the slice: it falls to the default arm")
      (is (not (valid? "DocN" (sec "tidAN" :title "t"))) "SecAN requires a note")
      (is (valid? "DocN" (sec "tidAN" :title "t" :note "n")))
      (is (= [[:body :component 0 :section :note]]
             (inh-errors (schema "DocN") options (sec "tidAN" :title "t")))))
    (testing "the unrestated slice is unchanged"
      (is (valid? "DocN" (sec "tidB" :note "n")))
      (is (not (valid? "DocN" (sec "tidB")))))
    (testing "nothing was left uncompiled"
      (is (empty? uncompiled)))))

(defn- sheet-sds
  "Sheet.entry sliced on the profile of `act`, slices p and q, at the root."
  []
  (conj (section-sds)
        (inh-sd "SecR" "Section" [(inh-str "Section.note" :min 1) (inh-str "Section.title" :min 1)])
        (inh-sd "Entry" nil [["Entry.act" {:type (inh-type "Section") :min 0 :max "1"}]])
        (inh-sd "SheetRoot" nil [["SheetRoot.entry" {:type (inh-type "Entry") :min 0 :max "*"}]])
        (inh-sd "Sheet" "SheetRoot"
                (into [["Sheet.entry" {:slicing {:discriminator [{:type "profile" :path "act"}] :rules "open"}}]]
                      (concat (slice-child "Sheet.entry" "p" "act" "SecA")
                              (slice-child "Sheet.entry" "q" "act" "SecB"))))))

(deftest restated-and-added-root-slices-test
  (let [hook (inh-profile-hook section-table)
        {:keys [schema options uncompiled]}
        (generate-inherited
         true hook
         (conj (sheet-sds)
               (inh-sd "SheetD" "Sheet"
                       (into [["Sheet.entry" {}]]
                             (concat (slice-child "Sheet.entry" "p" "act" "SecAN")
                                     (slice-child "Sheet.entry" "r" "act" "SecR"))))))
        multi (inh-multi (schema "SheetD") [:entry 0])
        dispatch (:dispatch (m/properties multi))
        valid? (fn [instance] (m/validate (schema "SheetD") instance options))
        entry (fn [tid & {:as extra}] {:entry [{:act (merge {:tid tid} extra)}]})]
    (testing "the restated arm keeps its inherited key, the added arm is keyed by its profile"
      (is (= [(inh-url "SecA") (inh-url "SecB") (inh-url "SecR") :malli.core/default]
             (inh-arm-keys multi))))
    (testing "the rebuilt dispatch routes the narrower, the unchanged and the added templateId"
      (is (= (inh-url "SecA") (dispatch {:act {:tid "tidAN"}})))
      (is (= (inh-url "SecB") (dispatch {:act {:tid "tidB"}})))
      (is (= (inh-url "SecR") (dispatch {:act {:tid "tidR"}}))))
    (testing "each arm holds its instance to its own profile"
      (is (not (valid? (entry "tidAN" :title "t"))))
      (is (valid? (entry "tidAN" :title "t" :note "n")))
      (is (not (valid? (entry "tidR" :title "t"))))
      (is (valid? (entry "tidR" :title "t" :note "n")))
      (is (not (valid? (entry "tidB"))))
      (is (valid? (entry "tidB" :note "n"))))
    (testing "an occurrence of no slice reaches the open default"
      (is (valid? (entry "other"))))
    (is (empty? uncompiled))))

(deftest restated-value-tuple-slice-keeps-its-key-test
  (let [fixed (fn [id v] [id {:type [{:code "string"}] :fixedString v :min 1 :max "1"}])
        {:keys [schema options uncompiled]}
        (generate-inherited
         true nil
         [(inh-sd "Part" nil [(inh-str "Part.cls") (inh-str "Part.sub") (inh-str "Part.extra")])
          (inh-sd "PtRoot" nil [["PtRoot.part" {:type (inh-type "Part") :min 0 :max "*"}]])
          (inh-sd "Pt" "PtRoot"
                  [["Pt.part" {:slicing {:discriminator [{:type "value" :path "cls"}
                                                         {:type "value" :path "sub"}]
                                         :rules "open"}}]
                   ["Pt.part:csm" {:sliceName "csm" :min 0 :max "*"}]
                   (fixed "Pt.part:csm.cls" "CSM")
                   (fixed "Pt.part:csm.sub" "MANU")])
          (inh-sd "PtD" "Pt"
                  [["Pt.part" {}]
                   ["Pt.part:csm" {:sliceName "csm" :min 0 :max "*"}]
                   (fixed "Pt.part:csm.cls" "CSM")
                   (fixed "Pt.part:csm.sub" "MANU")
                   (fixed "Pt.part:csm.extra" "MMAT")])])
        multi (inh-multi (schema "PtD") [:part 0])
        valid? (fn [instance] (m/validate (schema "PtD") instance options))]
    (is (= [["CSM" "MANU"] :malli.core/default] (inh-arm-keys multi))
        "the key stays the inherited pair, not the three values the differential states")
    (is (valid? {:part [{:cls "CSM" :sub "MANU" :extra "MMAT"}]}))
    (is (not (valid? {:part [{:cls "CSM" :sub "MANU" :extra "OTHER"}]}))
        "the extra fixed value below the slice is enforced")
    (is (valid? {:part [{:cls "CSM" :sub "OTHER" :extra "OTHER"}]})
        "an occurrence that is not the slice is unconstrained")
    (is (empty? uncompiled))))

(deftest added-value-slice-is-keyed-in-discriminator-order-test
  (let [fixed (fn [id v] [id {:type [{:code "string"}] :fixedString v :min 1 :max "1"}])
        {:keys [schema options uncompiled]}
        (generate-inherited
         true nil
         [(inh-sd "Ident" nil [(inh-str "Ident.root") (inh-str "Ident.extension") (inh-str "Ident.note")])
          (inh-sd "IddRoot" nil [["IddRoot.ident" {:type (inh-type "Ident") :min 0 :max "*"}]])
          (inh-sd "Idd" "IddRoot"
                  [["Idd.ident" {:slicing {:discriminator [{:type "value" :path "root"}
                                                           {:type "value" :path "extension"}]
                                           :rules "open"}}]
                   ["Idd.ident:one" {:sliceName "one" :min 1 :max "1"}]
                   (fixed "Idd.ident:one.root" "1.1")
                   (fixed "Idd.ident:one.extension" "e1")])
          (inh-sd "IddD" "Idd"
                  [["Idd.ident" {}]
                   ["Idd.ident:two" {:sliceName "two" :min 1 :max "1"}]
                   (fixed "Idd.ident:two.extension" "e2")
                   (fixed "Idd.ident:two.root" "2.2")
                   (inh-str "Idd.ident:two.note" :min 1)])])
        multi (inh-multi (schema "IddD") [:ident 0])
        valid? (fn [instance] (m/validate (schema "IddD") instance options))]
    (is (= [["1.1" "e1"] ["2.2" "e2"] :malli.core/default] (inh-arm-keys multi))
        "the added key follows the slicing's discriminators, not the differential's order")
    (is (not (valid? {:ident [{:root "2.2" :extension "e2"}]})) "the added arm requires its note")
    (is (valid? {:ident [{:root "2.2" :extension "e2" :note "n"}]}))
    (is (valid? {:ident [{:root "1.1" :extension "e1"}]}) "the inherited slice is untouched")
    (is (empty? uncompiled))))

(deftest hook-declining-the-merged-group-falls-back-in-place-test
  (let [hook (inh-profile-hook section-table)
        ;; SecX has no templateId in the table, so the hook declines the
        ;; rebuilt list: the restated arm still replaces the inherited one,
        ;; under the inherited dispatch, and the added slice is dropped.
        {:keys [schema options uncompiled]}
        (generate-inherited true hook
                            (conj (nested-base-sds)
                                  (derived-doc "DocD" "DocBase" [["a" "SecX"] ["c" "SecR"]])))
        multi (inh-multi (schema "DocD") [:body :component 0])
        sec (fn [tid & {:as extra}] {:body {:component [{:section (merge {:tid tid} extra)}]}})]
    (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default] (inh-arm-keys multi)))
    (is (= (inh-url "SecA") ((:dispatch (m/properties multi)) {:section {:tid "tidA"}}))
        "the inherited dispatch still routes the base templateId")
    (is (not (m/validate (schema "DocD") (sec "tidA" :title "t") options))
        "and the replaced arm holds it to SecX, which requires a note")
    (is (m/validate (schema "DocD") (sec "tidA" :title "t" :note "n") options))
    (is (= #{[:dispatch-declined "c"] [:restated-unroutable "a"]}
           (into #{} (map (juxt :reason :slice)) uncompiled)))
    (is (every? #(= "DocBase.body.component" (:element %)) uncompiled))))

(deftest grandchild-rebuilds-from-the-merged-record-test
  (let [hook (inh-profile-hook section-table)
        {:keys [schema options uncompiled entry]}
        (generate-inherited true hook
                            (conj (nested-base-sds)
                                  (derived-doc "DocN" "DocBase" [["a" "SecAN"]])
                                  (derived-doc "DocN2" "DocN" [["b" "SecBN"]])))
        multi (inh-multi (schema "DocN2") [:body :component 0])
        dispatch (:dispatch (m/properties multi))
        valid? (fn [instance] (m/validate (schema "DocN2") {:body {:component [{:section instance}]}} options))]
    (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default] (inh-arm-keys multi)))
    (is (= (inh-url "SecA") (dispatch {:section {:tid "tidAN"}})) "the first derivation's narrowing is kept")
    (is (= (inh-url "SecB") (dispatch {:section {:tid "tidBN"}})) "the second one is added")
    (is (nil? (dispatch {:section {:tid "tidB"}})))
    (is (not (valid? {:tid "tidAN" :title "t"})))
    (is (not (valid? {:tid "tidBN" :note "n"})) "SecBN requires a title")
    (is (valid? {:tid "tidBN" :note "n" :title "t"}))
    (is (empty? uncompiled))
    (is (= [(inh-url "SecA") (inh-url "SecB")]
           (mapv :key (:arms (get (:slicings (entry "DocN2")) "DocBase.body.component")))))))

(deftest nested-body-is-computed-against-the-live-arm-test
  ;; The inherited arm holds :section as a ref to SecA. A derived body that
  ;; constrains below :section has to deref it, which only the live arm (not
  ;; the core Component type the nested walk stands on) tells the generator.
  (let [hook (inh-profile-hook section-table)
        derived (inh-sd "DocBelow" "DocBase"
                        [["DocBase.body.component" {}]
                         ["DocBase.body.component:a" {:sliceName "a" :min 1 :max "1"}]
                         ["DocBase.body.component:a.section" {}]
                         ["DocBase.body.component:a.section.note" {:min 1}]])
        {:keys [schema options uncompiled]}
        (generate-inherited true hook (conj (nested-base-sds) derived))
        sec (fn [tid & {:as extra}] {:body {:component [{:section (merge {:tid tid :title "t"} extra)}]}})]
    (is (not (m/validate (schema "DocBelow") (sec "tidA") options)))
    (is (= [[:body :component 0 :section :note]]
           (inh-errors (schema "DocBelow") options (sec "tidA"))))
    (is (m/validate (schema "DocBelow") (sec "tidA" :note "n") options))
    (is (m/validate (schema "DocBelow") (sec "tidB" :note "n") options))
    (is (empty? uncompiled))))

(deftest gate-off-keeps-the-merged-arms-test
  (let [hook (inh-profile-hook section-table)
        nested (generate-inherited false hook
                                   (conj (nested-base-sds)
                                         (derived-doc "DocN" "DocBase" [["a" "SecAN"]])))
        root (generate-inherited false hook
                                 (conj (sheet-sds)
                                       (inh-sd "SheetD" "Sheet"
                                               (into [["Sheet.entry" {}]]
                                                     (slice-child "Sheet.entry" "p" "act" "SecAN")))))
        root-multi (inh-multi (get-in root [:schema "SheetD"]) [:entry 0])]
    (testing "no record is kept"
      (is (every? #(not (contains? % :slicings)) (vals (:entry nested))))
      (is (every? #(not (contains? % :slicings)) (vals (:entry root)))))
    (testing "nested: the restated slice never reaches the arm for its templateId"
      (let [multi (inh-multi (get-in nested [:schema "DocN"]) [:body :component 0])]
        (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default] (inh-arm-keys multi)))
        (is (nil? ((:dispatch (m/properties multi)) {:section {:tid "tidAN"}})))))
    (testing "root: the restated slice is keyed by the derived profile, which the inherited table never returns"
      (is (= [(inh-url "SecA") (inh-url "SecB") :malli.core/default (inh-url "SecAN")]
             (inh-arm-keys root-multi)))
      (is (nil? ((:dispatch (m/properties root-multi)) {:act {:tid "tidAN"}}))))
    (is (empty? (:uncompiled nested)))))

(deftest slices-the-rebuild-cannot-honor-are-reported-test
  (let [hook (inh-profile-hook section-table)
        body (fn [n rules slices]
               (inh-sd n nil
                       (into [["Doc.body" {:type (inh-type "Body") :min 0 :max "1"}]
                              ["Doc.body.component"
                               {:slicing {:discriminator [{:type "profile" :path "section"}]
                                          :rules rules}}]]
                             slices)))
        closed (body "ClosedBase" "closed"
                     (concat (slice-section "Doc.body.component" "a" "SecA")
                             (slice-section "Doc.body.component" "b" "SecB")))]
    (testing "an added slice on a closed slicing"
      (let [{:keys [schema uncompiled]}
            (generate-inherited true hook
                                (conj (section-sds) closed
                                      (inh-sd "ClosedD" "ClosedBase"
                                              (into [["Doc.body.component" {}]]
                                                    (slice-section "Doc.body.component" "c" "SecR")))))
            multi (inh-multi (schema "ClosedD") [:body :component 0])]
        (is (= [(inh-url "SecA") (inh-url "SecB")] (inh-arm-keys multi)) "no default arm, no new arm")
        (is (= [{:reason :closed-slicing :slice "c" :element "Doc.body.component"}]
               (mapv #(select-keys % [:reason :slice :element]) uncompiled)))))
    (testing "a restated slice whose key another slice won"
      (let [dup (body "DupBase" "open"
                      (concat (slice-section "Doc.body.component" "a" "SecA")
                              (slice-section "Doc.body.component" "a2" "SecA")))
            {:keys [schema uncompiled entry]}
            (generate-inherited true hook
                                (conj (section-sds) dup
                                      (inh-sd "DupD" "DupBase"
                                              (into [["Doc.body.component" {}]]
                                                    (slice-section "Doc.body.component" "a" "SecAN")))))]
        (is (= [{:reason :collided-restated :slice "a"}]
               (mapv #(select-keys % [:reason :slice]) uncompiled)))
        (is (= [true false]
               (mapv :collided? (:arms (get (:slicings (entry "DupBase")) "Doc.body.component")))))))
    (testing "an added slice on a wildcard exists slicing"
      (let [wild (inh-sd "WildBase" nil
                         [["Doc.entry" {:type [{:code (inh-url "Entry2")} ] :min 0 :max "*"
                                        :slicing {:discriminator [{:type "exists" :path "*"}] :rules "open"}}]
                          ["Doc.entry:x" {:sliceName "x" :min 0 :max "*"}]
                          ["Doc.entry:x.act" {:min 1}]
                          ["Doc.entry:y" {:sliceName "y" :min 0 :max "*"}]
                          ["Doc.entry:y.obs" {:min 1}]])
            {:keys [uncompiled]}
            (generate-inherited true hook
                                [(inh-sd "Entry2" nil [(inh-str "Entry2.act") (inh-str "Entry2.obs")])
                                 (inh-sd "WildRoot" nil [["Doc.entry" {:type (inh-type "Entry2") :min 0 :max "*"}]])
                                 (assoc-in wild [:baseDefinition] (inh-url "WildRoot"))
                                 (inh-sd "WildD" "WildBase"
                                         [["Doc.entry" {}]
                                          ["Doc.entry:z" {:sliceName "z" :min 0 :max "*"}]
                                          ["Doc.entry:z.act" {:min 1}]
                                          ["Doc.entry:z.obs" {:min 1}]])])]
        (is (= [{:reason :wildcard-group :slice "z"}]
               (mapv #(select-keys % [:reason :slice]) uncompiled)))))))

(defn- updates-field?
  "Whether a generated form contains a (mu/update <sch> field ...) step."
  [form field]
  (boolean (some #(and (seq? %) (= 'mu/update (first %)) (= field (second %)))
                 (tree-seq coll? seq form))))

(deftest declined-multi-is-threaded-only-on-a-field-that-does-not-repeat-test
  (let [hook (constantly nil)
        sec (fn [& {:as extra}] (merge {:tid "tidA" :title "t"} extra))
        repeating (generate-inherited
                   true hook
                   (conj (nested-base-sds) (derived-doc "DocN" "DocBase" [["a" "SecAN"]])))
        single-base (inh-sd "OneBase" nil
                            [["OneBase.act" {:type (inh-type "Section") :min 0 :max "1"
                                             :slicing {:discriminator [{:type "profile" :path "$this"}]
                                                       :rules "open"}}]
                             ["OneBase.act:a" {:sliceName "a" :type [{:code (inh-url "Section")
                                                                      :profile [(inh-url "SecA")]}]
                                               :min 0 :max "1"}]])
        single (generate-inherited
                true hook
                (conj (section-sds) single-base
                      (inh-sd "OneD" "OneBase"
                              [["OneBase.act" {}]
                               ["OneBase.act:a" {:sliceName "a" :min 0 :max "1"}]
                               ["OneBase.act:a.note" {:type [{:code "string"}] :min 1 :max "1"}]])))]
    (testing "a repeating field: the slice is reported and no threading form is emitted"
      (is (= [{:reason :no-multi-repeating :slice "a" :element "DocBase.body.component"}]
             (mapv #(select-keys % [:reason :slice :element]) (:uncompiled repeating))))
      (is (m/validate (get-in repeating [:schema "DocN"])
                      {:body {:component [{:section (sec)}]}} (:options repeating))
          "an occurrence the slice would have constrained is left alone")
      (is (not (updates-field? (get-in repeating [:entry "DocN" :form]) :section))))
    (testing "a field that does not repeat keeps the direct threading"
      (is (empty? (:uncompiled single)))
      (is (updates-field? (get-in single [:entry "OneD" :form]) :act)
          "the form updates the sliced field itself"))))

;; ---------------------------------------------------------------------------
;; Slice counts (doc/inherited-slices-and-slice-counts.md section 5)
;; ---------------------------------------------------------------------------

(def ^:private count-slices
  "Slices a 1..1, b 0..1, c 1..*, z 0..0, each fixing `code`."
  [["a" "1" "1"] ["b" "0" "1"] ["c" "1" "*"] ["z" "0" "0"]])

(defn- fixed-code [id v]
  [id {:type [{:code "string"}] :fixedString v :min 1 :max "1"}])

(defn- counted-sds
  "CRoot.ident (0..*) sliced by `code` in C, with the slices of `count-slices`;
   a slice's fixed code is its name upcased."
  [rules]
  [(inh-sd "Ident" nil [(inh-str "Ident.code") (inh-str "Ident.note")])
   (inh-sd "CRoot" nil [["CRoot.ident" {:type (inh-type "Ident") :min 0 :max "*"}]])
   (inh-sd "C" "CRoot"
           (into [["C.ident" {:slicing {:discriminator [{:type "value" :path "code"}] :rules rules}}]]
                 (mapcat (fn [[n mn mx]]
                           [[(str "C.ident:" n) {:sliceName n :min (parse-long mn) :max mx}]
                            (fixed-code (str "C.ident:" n ".code") (str/upper-case n))])
                         count-slices)))])

(defn- entry-optional [sch k]
  (let [[_ props] (first (filter #(= k (first %)) (m/children sch)))]
    (get props :optional :absent)))

(defn- ident [& codes] {:ident (mapv (fn [c] {:code c}) codes)})

(defn- count-errors
  "[in slice-name slice-id] of every count error `m/explain` reports."
  [sch options instance]
  (->> (:errors (m/explain sch instance options))
       (keep (fn [{:keys [in schema]}]
               (when-let [n (:fhir/slice-name (m/properties schema))]
                 [in n (:fhir/slice-id (m/properties schema))])))
       sort
       vec))

(deftest slice-counts-on-an-open-slicing-test
  (let [{:keys [schema options uncompiled entry]} (generate-inherited true nil (counted-sds "open") :counts? true)
        sch (schema "C")
        field (mu/get sch :ident)
        errs (partial count-errors sch options)
        valid? #(m/validate sch % options)]
    (testing "the field is a counted :and whose child 0 is the sequential of arms"
      (is (= :and (m/type field)))
      (is (= [["A" "a" 1 1] ["B" "b" 0 1] ["C" "c" 1 nil] ["Z" "z" 0 0]]
             (:fhir/slice-counts (m/properties field))))
      (is (= (conj ["A" "B" "C" "Z"] :malli.core/default)
             (inh-arm-keys (inh-multi sch [:ident 0 0])))
          "the routing arms are unchanged")
      (is (fdm/counted-sequential field))
      (is (= :multi (m/type (fdm/unwrap-sequential field)))))
    (testing "a mandatory slice makes the key itself required"
      (is (false? (entry-optional sch :ident)))
      (is (not (valid? {})))
      (is (some? (m/explain sch {}))))
    (testing "zero occurrences of the 1..1 and the 1..* slice"
      (is (= [[[:ident] "a" "C.ident:a"] [[:ident] "c" "C.ident:c"]]
             (errs {:ident []}))
          "one error per failing slice, at the sliced field, naming the slice and its element id"))
    (testing "one occurrence of each mandatory slice, none of the optional ones"
      (is (valid? (ident "A" "C"))))
    (testing "two occurrences"
      (is (= [[[:ident] "a" "C.ident:a"]] (errs (ident "A" "A" "C"))) "1..1")
      (is (= [[[:ident] "b" "C.ident:b"]] (errs (ident "A" "B" "B" "C"))) "0..1")
      (is (valid? (ident "A" "C" "C" "C")) "1..* is unbounded")
      (is (= [[[:ident] "z" "C.ident:z"]] (errs (ident "A" "C" "Z"))) "max 0 rejects the first"))
    (testing "occurrences of no slice reach the open default arm and are not counted"
      (is (valid? (ident "A" "C" "other" "other"))))
    (testing "a slice's own constraints still apply through its arm"
      (is (not (m/validate sch {:ident [{:code "A" :note 1} {:code "C"}]} options))))
    (is (empty? uncompiled))
    (is (true? (get-in entry ["C" :slicings "C.ident" :counted?]))
        "the record says the field is counted, for a derivation to unwrap")))

(deftest slice-counts-on-a-closed-slicing-test
  (let [{:keys [schema options uncompiled]} (generate-inherited true nil (counted-sds "closed") :counts? true)
        sch (schema "C")
        field (mu/get sch :ident)]
    (is (= :and (m/type field)))
    (is (= ["A" "B" "C" "Z"] (inh-arm-keys (inh-multi sch [:ident 0 0]))) "no default arm")
    (is (m/validate sch (ident "A" "C") options))
    (testing "an unmatched occurrence is rejected by the :multi, and the counts are unchanged"
      (let [errs (:errors (m/explain sch (ident "A" "C" "other") options))]
        (is (= [[:ident 2]] (mapv #(vec (take 2 (:in %))) errs)))
        (is (empty? (count-errors sch options (ident "A" "C" "other"))))))
    (is (= [[[:ident] "a" "C.ident:a"]] (count-errors sch options (ident "A" "A" "C"))))
    (is (empty? uncompiled))))

(deftest slice-counts-gate-off-emits-the-plain-sequential-test
  (let [{:keys [schema]} (generate-inherited true nil (counted-sds "open"))
        field (mu/get (schema "C") :ident)]
    (is (= :sequential (m/type field)))
    (is (nil? (fdm/counted-sequential field)))
    (is (true? (entry-optional (schema "C") :ident)) "the field stays optional")))

(deftest slice-counts-require-the-rebuild-gate-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires \*rebuild-inherited-slicings\*"
                        (generate-inherited false nil (counted-sds "open") :counts? true))))

(deftest slice-counts-follow-a-narrowed-restatement-test
  (let [derived (fn [n base slices]
                  (inh-sd n base
                          (into [["C.ident" {}]]
                                (mapcat (fn [[sn mn mx]]
                                          [[(str "C.ident:" sn) {:sliceName sn :min mn :max mx}]
                                           (fixed-code (str "C.ident:" sn ".code") (str/upper-case sn))]))
                                slices)))
        {:keys [schema options uncompiled entry]}
        (generate-inherited true nil
                            (conj (counted-sds "open")
                                  (derived "CD" "C" [["b" 1 "1"]])
                                  (derived "CDD" "CD" [["z" 0 "1"]]))
                            :counts? true)
        counts (fn [n] (:fhir/slice-counts (m/properties (mu/get (schema n) :ident))))]
    (testing "0..1 narrowed to 1..1 is counted as 1..1, and the field is still counted"
      (is (= [["A" "a" 1 1] ["B" "b" 1 1] ["C" "c" 1 nil] ["Z" "z" 0 0]] (counts "CD")))
      (is (= :and (m/type (mu/get (schema "CD") :ident))))
      (is (= [[[:ident] "b" "C.ident:b"]] (count-errors (schema "CD") options (ident "A" "C"))))
      (is (m/validate (schema "CD") (ident "A" "B" "C") options))
      (is (m/validate (schema "C") (ident "A" "C") options) "the base is untouched"))
    (testing "a grandchild rebuilds from the merged record, counting from the snapshot-less differential"
      (is (= [["A" "a" 1 1] ["B" "b" 1 1] ["C" "c" 1 nil] ["Z" "z" 0 1]] (counts "CDD")))
      (is (m/validate (schema "CDD") (ident "A" "B" "C" "Z") options)))
    (is (= (inh-arm-keys (inh-multi (schema "C") [:ident 0 0]))
           (inh-arm-keys (inh-multi (schema "CD") [:ident 0 0]))))
    (is (empty? uncompiled))))

(deftest slice-counts-start_at_a_derivation-test
  (let [{:keys [schema options entry]}
        (generate-inherited
         true nil
         [(inh-sd "Ident" nil [(inh-str "Ident.code")])
          (inh-sd "KRoot" nil [["KRoot.ident" {:type (inh-type "Ident") :min 0 :max "*"}]])
          (inh-sd "K" "KRoot"
                  [["K.ident" {:slicing {:discriminator [{:type "value" :path "code"}] :rules "open"}}]
                   ["K.ident:p" {:sliceName "p" :min 0 :max "*"}]
                   (fixed-code "K.ident:p.code" "P")])
          (inh-sd "KD" "K"
                  [["K.ident" {}]
                   ["K.ident:p" {:sliceName "p" :min 1 :max "*"}]
                   (fixed-code "K.ident:p.code" "P")])]
         :counts? true)]
    (is (= :sequential (m/type (mu/get (schema "K") :ident))) "0..* counts nothing")
    (is (= :and (m/type (mu/get (schema "KD") :ident))) "the derived profile starts counting")
    (is (not (m/validate (schema "KD") {:ident [{:code "other"}]} options)))
    (is (m/validate (schema "KD") {:ident [{:code "P"} {:code "P"}]} options))
    (is (false? (get-in entry ["K" :slicings "K.ident" :counted?])))
    (is (true? (get-in entry ["KD" :slicings "K.ident" :counted?])))))

(deftest slice-counts-skip-what-the-dispatch-cannot-count-test
  (let [{:keys [schema options uncompiled]}
        (generate-inherited
         true nil
         [(inh-sd "Ident" nil [(inh-str "Ident.code") (inh-str "Ident.note")])
          (inh-sd "URoot" nil [["URoot.ident" {:type (inh-type "Ident") :min 0 :max "*"}]])
          (inh-sd "U" "URoot"
                  [["U.ident" {:slicing {:discriminator [{:type "value" :path "code"}] :rules "open"}}]
                   ["U.ident:a" {:sliceName "a" :min 0 :max "*"}]
                   (fixed-code "U.ident:a.code" "A")
                   ;; no value at the discriminator's path: keyed :d, no dispatch can return it
                   ["U.ident:d" {:sliceName "d" :min 1 :max "1"}]
                   (inh-str "U.ident:d.note" :min 1)
                   ;; two slices sharing a key
                   ["U.ident:e" {:sliceName "e" :min 1 :max "1"}]
                   (fixed-code "U.ident:e.code" "E")
                   ["U.ident:e2" {:sliceName "e2" :min 0 :max "1"}]
                   (fixed-code "U.ident:e2.code" "E")])]
         :counts? true)
        sch (schema "U")]
    (is (= :and (m/type (mu/get sch :ident))) "the two slices sharing a key are still counted")
    (is (= #{[:count-unroutable-key "d"]}
           (into #{} (map (juxt :reason :slice)) uncompiled))
        "a key two slices share is counted for both: a count is over the matches")
    (testing "an occurrence that lacks the discriminating value still validates the arms"
      (is (empty? (count-errors sch options {:ident [{:code "E"}]})))
      (is (= #{"e" "e2"} (set (map second (count-errors sch options {:ident [{:code "E"} {:code "E"}]}))))
          "two E occurrences exceed the 0..1 of e2 and the 1..1 of e"))))

(deftest nested-restatement-of-a-counted-field-keeps-it-counted-test
  (let [{:keys [schema options]}
        (generate-inherited
         true nil
         (conj (counted-sds "open")
               (inh-sd "CN" "C" [["C.ident" {}] (inh-str "C.ident.note" :min 1)]))
         :counts? true)
        sch (schema "CN")]
    (is (= :and (m/type (mu/get sch :ident))) "only entry properties were emitted")
    (is (m/validate sch (ident "A" "C") options))
    (is (= [:ident 0 :code]
           (#'fdm/discriminator-path->get-in-path (schema "C") "ident.code"))
        "a path through a counted field gets its 0 index")))

(deftest slice-counts-decline-a-field-that-does-not-repeat-test
  (let [arm {:slice-name "q" :min 1 :max "1" :key "Q" :routable? true}
        run (fn [group]
              (binding [fdm/*uncompiled-slices* (atom [])]
                [(#'fdm/counted-arms group "X.one" any?)
                 (mapv :reason @fdm/*uncompiled-slices*)]))]
    (is (= [[arm] []] (run {:arms [arm] :multi? true :field-is-sequential? true})))
    (is (= [[] [:count-non-repeating]] (run {:arms [arm] :multi? true :field-is-sequential? false}))
        "a single value has no occurrences to count")
    (is (= [[] [:count-no-multi]] (run {:arms [arm] :multi? false :field-is-sequential? true})))
    (is (= [[] []] (run {:arms [(assoc arm :min 0 :max "*")] :multi? false}))
        "a slice with no bound is not wanted, so nothing is reported")))

;; Round 2: a count is over the occurrences that MATCH a slice
;; (doc/inherited-slices-and-slice-counts.md, Round 2).

(defn- tag-sds
  "TRoot.item (0..*) of Item, whose `tag` repeats, sliced by the value of tag:
   a 1..1 and b 0..1 (open), c 1..1 (closed variant uses `rules`)."
  [rules]
  [(inh-sd "Item" nil [["Item.tag" {:type [{:code "string"}] :min 0 :max "*"}]])
   (inh-sd "TRoot" nil [["TRoot.item" {:type (inh-type "Item") :min 0 :max "*"}]])
   (inh-sd "T" "TRoot"
           [["T.item" {:slicing {:discriminator [{:type "value" :path "tag"}] :rules rules}}]
            ["T.item:a" {:sliceName "a" :min 1 :max "1"}]
            ["T.item:a.tag" {:type [{:code "string"}] :fixedString "A" :min 1 :max "*"}]
            ["T.item:b" {:sliceName "b" :min 1 :max "1"}]
            ["T.item:b.tag" {:type [{:code "string"}] :fixedString "B" :min 1 :max "*"}]])])

(defn- items [& tag-lists] {:item (mapv (fn [tags] {:tag (vec tags)}) tag-lists)})

(deftest slice-counts-count-every-matching-slice-test
  (let [{:keys [schema options]} (generate-inherited true nil (tag-sds "open") :counts? true)
        sch (schema "T")
        errs (partial count-errors sch options)]
    (is (= :and (m/type (mu/get sch :item))))
    (testing "one occurrence that matches two slices satisfies both 1..1 counts"
      (is (empty? (errs (items ["B" "A"]))) "the dispatch routes it to one arm only")
      (is (empty? (errs (items ["A" "B"])))))
    (testing "the occurrence is counted for each slice it matches, so a second one exceeds both"
      (is (= [[[:item] "a" "T.item:a"] [[:item] "b" "T.item:b"]]
             (errs (items ["A" "B"] ["B" "A"])))))
    (testing "an occurrence matching one slice counts for that slice only"
      (is (= [[[:item] "b" "T.item:b"]] (errs (items ["A"]))))
      (is (empty? (errs (items ["A"] ["B"])))))
    (testing "open slicing: an occurrence matching no slice is allowed and counts for none"
      (is (empty? (errs (items ["A"] ["B"] ["other"] [] ["other" "more"])))))))

(deftest slice-counts-claims-hook-test
  (let [table {(inh-url "SecP") "tidP" (inh-url "SecQ") "tidQ"}
        paths (fn [path] (conj (vec path) :tids))
        hook (fn [{:keys [type path arms]}]
               (when (= "profile" type)
                 (let [entries (mapv (fn [{:keys [dispatch-value profiles]}]
                                       [(table (first (or (seq profiles) [dispatch-value]))) dispatch-value])
                                     arms)]
                   `(~'fn [~'m]
                     (~'let [~'tids (set (~'get-in ~'m ~(paths path)))]
                      (~'some (~'fn [[~'t ~'v]] (~'when (~'contains? ~'tids ~'t) ~'v)) ~entries))))))
        claims (fn [{:keys [type path arms]}]
                 (when (= "profile" type)
                   (let [entries (mapv (fn [{:keys [dispatch-value profiles]}]
                                         [(table (first (or (seq profiles) [dispatch-value]))) dispatch-value])
                                       arms)]
                     `(~'fn [~'m]
                       (~'let [~'tids (set (~'get-in ~'m ~(paths path)))]
                        (~'into #{} (~'keep (~'fn [[~'t ~'v]] (~'when (~'contains? ~'tids ~'t) ~'v))) ~entries))))))
        sds [(inh-sd "Sec" nil [["Sec.tids" {:type [{:code "string"}] :min 0 :max "*"}]])
             (inh-sd "SecP" "Sec" [])
             (inh-sd "SecQ" "Sec" [])
             (inh-sd "Comp" nil [["Comp.section" {:type (inh-type "Sec") :min 0 :max "1"}]])
             (inh-sd "HRoot" nil [["HRoot.component" {:type (inh-type "Comp") :min 0 :max "*"}]])
             (inh-sd "H" "HRoot"
                     (into [["H.component" {:slicing {:discriminator [{:type "profile" :path "section"}]
                                                      :rules "open"}}]]
                           (mapcat (fn [n] (slice-section "H.component" (str/lower-case n) (str "Sec" n)))
                                   ["P" "Q"])))]
        run (fn [claims-fn]
              (binding [fdm/*discriminator-claims-fn* claims-fn]
                (generate-inherited true hook sds :counts? true)))
        comp (fn [& tids] {:section {:tids (vec tids)}})]
    (testing "a component claiming both templates satisfies both 1..1 counts"
      (let [{:keys [schema options]} (run claims)
            sch (schema "H")
            errs (partial count-errors sch options)]
        (is (empty? (errs {:component [(comp "tidP" "tidQ")]})))
        (is (= [[[:component] "q" "H.component:q"]] (errs {:component [(comp "tidP")]})))
        (is (= [[[:component] "p" "H.component:p"] [[:component] "q" "H.component:q"]]
               (errs {:component [(comp "tidP" "tidQ") (comp "tidQ" "tidP")]})))
        (is (empty? (errs {:component [(comp "tidP") (comp "tidQ") (comp "other")]}))
            "open slicing allows a component claiming neither")))
    (testing "without a claims hook the count falls back to the dispatch's value"
      (let [{:keys [schema options]} (run nil)]
        (is (seq (count-errors (schema "H") options {:component [(comp "tidP" "tidQ")]}))
            "the dispatch picks one slice, so the other is missing")))))

(deftest slice-counts-soft-discriminator-drift-test
  (let [sds [(inh-sd "Tid" nil [(inh-str "Tid.root") (inh-str "Tid.ver")])
             (inh-sd "DRoot" nil [["DRoot.tid" {:type (inh-type "Tid") :min 0 :max "*"}]])
             (inh-sd "D" "DRoot"
                     [["D.tid" {:slicing {:discriminator [{:type "value" :path "root"}
                                                          {:type "value" :path "ver"}]
                                          :rules "open"}}]
                      ["D.tid:a" {:sliceName "a" :min 1 :max "1"}]
                      (fixed-code "D.tid:a.root" "R")
                      (fixed-code "D.tid:a.ver" "2")])]
        run (fn [soft]
              (binding [fdm/*count-soft-discriminator-fn* soft]
                (generate-inherited true nil sds :counts? true)))
        tids (fn [& pairs] {:tid (mapv (fn [[r v]] (cond-> {:root r} v (assoc :ver v))) pairs)})
        soft (fn [{:keys [element-id path]}] (and (= "D.tid" element-id) (= "ver" path)))]
    (testing "no soft discriminator: only the exact claim counts"
      (let [{:keys [schema options]} (run nil)
            errs (partial count-errors (schema "D") options)]
        (is (empty? (errs (tids ["R" "2"]))))
        (is (= [[[:tid] "a" "D.tid:a"]] (errs (tids ["R" "1"]))))))
    (testing "a soft discriminator: a drifted claim counts once when no exact claim exists"
      (let [{:keys [schema options]} (run soft)
            errs (partial count-errors (schema "D") options)]
        (is (empty? (errs (tids ["R" "2"]))) "exact")
        (is (empty? (errs (tids ["R" "1"]))) "another revision")
        (is (empty? (errs (tids ["R" nil]))) "unversioned")
        (is (empty? (errs (tids ["R" "1"] ["R" nil]))) "two drifted claims are one claim")
        (is (empty? (errs (tids ["R" "2"] ["R" nil]))) "the unversioned claim beside the exact one is not counted")
        (is (= [[[:tid] "a" "D.tid:a"]] (errs (tids ["R" "2"] ["R" "2"]))) "two exact claims exceed 1..1")
        (is (= [[[:tid] "a" "D.tid:a"]] (errs (tids ["S" "2"]))) "the hard discriminator still decides")
        (is (= [[[:tid] "a" "D.tid:a"]] (errs {:tid []})))))))

;; ---------------------------------------------------------------------------
;; Defaults: a caller that binds nothing gets origin/main's output
;; ---------------------------------------------------------------------------

(def ^:private origin-main-forms
  "The emitted forms of the three scenarios of `default-scenarios`, printed by
   origin/main 7eba52a (before the inherited-slicing and slice-count work) for
   the same StructureDefinitions. Measured 2026-10-26: 3 scenarios, 14 forms,
   byte-identical to this branch with no switch bound."
  (edn/read-string (slurp (io/resource "com/breezeehr/origin_main_forms.edn"))))

(defn- default-scenarios []
  {"nested" (conj (nested-base-sds) (derived-doc "DocN" "DocBase" [["a" "SecAN"]]))
   "root" (conj (sheet-sds)
                (inh-sd "SheetD" "Sheet"
                        (into [["Sheet.entry" {}]]
                              (concat (slice-child "Sheet.entry" "p" "act" "SecAN")
                                      (slice-child "Sheet.entry" "r" "act" "SecR")))))
   "counted" (counted-sds "open")})

(deftest unbound-switches-reproduce-origin-main-test
  (let [hook (inh-profile-hook section-table)
        forms (into {}
                    (for [[scenario sds] (default-scenarios)
                          [n entry] (:entry (generate-inherited nil hook sds))]
                      [(str scenario "/" n) (:form entry)]))]
    (testing "every new switch defaults off"
      (is (false? fdm/*rebuild-inherited-slicings*))
      (is (false? fdm/*compile-slice-counts*))
      (is (false? fdm/*max-on-sequence*))
      (is (false? fdm/*primitive-pattern-as-fixed*))
      (is (false? fdm/*honor-slice-root-profile*))
      (is (nil? fdm/*discriminator-claims-fn*))
      (is (nil? fdm/*count-soft-discriminator-fn*))
      (is (nil? fdm/*uncompiled-slices*))
      (is (nil? fdm/*slicings*)))
    (testing "the restated, added and counted slices emit what origin/main emitted"
      (is (= (set (keys origin-main-forms)) (set (keys forms))))
      (doseq [[k f] forms]
        (is (= (origin-main-forms k) f) k)))
    (testing "no slicing record is kept"
      (is (every? #(not (contains? % :slicings))
                  (mapcat (fn [[_ sds]] (vals (:entry (generate-inherited nil hook sds))))
                          (default-scenarios)))))))

;; ---------------------------------------------------------------------------
;; Slice-position elements whose type lists several profiles
;; ---------------------------------------------------------------------------

(defn- multi-slice-sds
  "Sheet2.entry sliced on the fixed code of each entry; slice p holds an `act`
   typed under `p-profiles`, slice q under SecR. `extra` are further elements."
  [p-profiles & extra]
  (let [slice-def (fn [slice code profiles]
                    [[(str "Sheet2.entry:" slice) {:sliceName slice :min 0 :max "*"}]
                     [(str "Sheet2.entry:" slice ".code")
                      {:type [{:code "string"}] :fixedString code :min 1 :max "1"}]
                     [(str "Sheet2.entry:" slice ".act")
                      {:type [{:code (inh-url "Section") :profile (mapv inh-url profiles)}]}]])]
    (conj (section-sds)
          (inh-sd "Entry2" nil [(inh-str "Entry2.code")
                                ["Entry2.act" {:type (inh-type "Section") :min 0 :max "1"}]])
          (inh-sd "Root2" nil [["Root2.entry" {:type (inh-type "Entry2") :min 0 :max "*"}]])
          (inh-sd "Sheet2" "Root2"
                  (into [["Sheet2.entry" {:slicing {:discriminator [{:type "value" :path "code"}]
                                                    :rules "open"}}]]
                        (concat (slice-def "p" "P" p-profiles)
                                (slice-def "q" "Q" ["SecR"])
                                extra))))))

(defn- entry2 [code tid & {:as extra}]
  {:entry [{:code code :act (merge {:tid tid} extra)}]})

(deftest multi-profile-slice-compiles-to-a-dispatched-union-test
  (let [hook (inh-profile-hook section-table)
        {:keys [schema options uncompiled]} (generate-inherited true hook (multi-slice-sds ["SecA" "SecB"]))
        valid? (fn [instance] (m/validate (schema "Sheet2") instance options))
        act (fn [sch] (mu/get (inh-multi sch [:entry 0]) :act))
        p-arm (some (fn [[k _ s]] (when (= "P" k) s)) (m/children (inh-multi (schema "Sheet2") [:entry 0])))]
    (testing "slice p's act is a profile union, not the core Section"
      (is (= :multi (m/type (m/deref (mu/get p-arm :act)))))
      (is (true? (:fhir/profile-union (m/properties (m/deref (mu/get p-arm :act)))))))
    (testing "an instance is held to the profile its templateId names"
      (is (valid? (entry2 "P" "tidA" :title "t")))
      (is (not (valid? (entry2 "P" "tidA"))) "SecA requires a title")
      (is (valid? (entry2 "P" "tidB" :note "n")))
      (is (not (valid? (entry2 "P" "tidB"))) "SecB requires a note"))
    (testing "an occurrence claiming no listed profile is held to any-of"
      (is (valid? (entry2 "P" "other" :title "t")) "conforms to SecA")
      (is (valid? (entry2 "P" "other" :note "n")) "conforms to SecB")
      (is (not (valid? (entry2 "P" "other"))) "conforms to neither"))
    (testing "the other slice and the open default are untouched"
      (is (not (valid? (entry2 "Q" "tidR"))))
      (is (valid? (entry2 "Q" "tidR" :title "t" :note "n")))
      (is (valid? (entry2 "X" "tidB"))))
    (is (empty? uncompiled))))

(deftest multi-profile-slice-falls-back-to-or-without-a-dispatch-test
  (let [hook (let [h (inh-profile-hook section-table)] (fn [m] (when (:path m) (h m))))
        {:keys [schema options uncompiled]} (generate-inherited true hook (multi-slice-sds ["SecA" "SecB"]))
        valid? (fn [instance] (m/validate (schema "Sheet2") instance options))
        p-arm (some (fn [[k _ s]] (when (= "P" k) s)) (m/children (inh-multi (schema "Sheet2") [:entry 0])))]
    (is (= :or (m/type (m/deref (mu/get p-arm :act)))))
    (is (valid? (entry2 "P" "tidA" :title "t")))
    (is (valid? (entry2 "P" "tidB" :note "n")))
    (is (not (valid? (entry2 "P" "tidA"))) "neither profile holds")
    (is (empty? uncompiled))))

(deftest restated-multi-profile-slice-is-a-union-too-test
  (let [hook (inh-profile-hook section-table)
        sds (conj (multi-slice-sds ["SecA" "SecB"])
                  (inh-sd "Sheet2D" "Sheet2"
                          [["Sheet2.entry" {}]
                           ["Sheet2.entry:p" {:sliceName "p" :min 0 :max "*"}]
                           ["Sheet2.entry:p.act"
                            {:type [{:code (inh-url "Section")
                                     :profile [(inh-url "SecAN") (inh-url "SecBN")]}]}]]))
        {:keys [schema options uncompiled]} (generate-inherited true hook sds)
        valid? (fn [instance] (m/validate (schema "Sheet2D") instance options))]
    (testing "the narrower profiles apply, dispatched by their templateIds"
      (is (valid? (entry2 "P" "tidAN" :title "t" :note "n")))
      (is (not (valid? (entry2 "P" "tidAN" :title "t"))) "SecAN requires a note")
      (is (valid? (entry2 "P" "tidBN" :title "t" :note "n")))
      (is (not (valid? (entry2 "P" "tidBN" :note "n"))) "SecBN requires a title"))
    (testing "the unrestated slice is unchanged"
      (is (not (valid? (entry2 "Q" "tidR"))))
      (is (valid? (entry2 "Q" "tidR" :title "t" :note "n"))))
    (is (empty? uncompiled))))

(deftest multi-profile-slice-degradations-are-reported-test
  (let [hook (inh-profile-hook section-table)
        reasons (fn [uncompiled] (mapv :reason uncompiled))]
    (testing "a profile no package defines keeps the core type and says so"
      (let [{:keys [schema options uncompiled]}
            (generate-inherited true hook
                                (multi-slice-sds ["SecA" "Missing"]))]
        (is (= [:multi-profile-unresolved] (reasons uncompiled)))
        (is (m/validate (schema "Sheet2") (entry2 "P" "tidA") options)
            "the element is the plain Section")))
    (testing "constraints below the element keep the core type and say so"
      (let [{:keys [uncompiled]}
            (generate-inherited true hook
                                (multi-slice-sds ["SecA" "SecB"]
                                                 ["Sheet2.entry:p.act.title" {:min 1 :max "1"}]))]
        (is (= [:multi-profile-with-constraints] (reasons uncompiled)))))
    (testing "a non-slice child with several profiles is never narrowed"
      (let [{:keys [schema options uncompiled]}
            (generate-inherited true hook
                                (conj (section-sds)
                                      (inh-sd "Plain" nil
                                              [["Plain.act" {:type [{:code (inh-url "Section")
                                                                     :profile [(inh-url "SecA") (inh-url "SecB")]}]
                                                             :min 1 :max "1"}]])))]
        (is (m/validate (schema "Plain") {:act {:tid "tidA"}} options))
        (is (empty? uncompiled))))
    (testing "the rebuild switch off leaves the core type, reporting nothing"
      (let [{:keys [schema options uncompiled]}
            (generate-inherited false hook (multi-slice-sds ["SecA" "SecB"]))]
        (is (m/validate (schema "Sheet2") (entry2 "P" "tidA") options))
        (is (empty? uncompiled))))))
