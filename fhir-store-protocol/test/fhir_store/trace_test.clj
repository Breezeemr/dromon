(ns fhir-store.trace-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]))

(def patient
  {:resourceType "Patient"
   :id "p1"
   :name [{:family "Testerson" :given ["Alice"]}]
   :birthDate "1980-02-03"})

(def coverage
  {:resourceType "Coverage"
   :id "c1"
   :subscriberId "ZXQ123456789"
   :beneficiary {:reference "Patient/p1"}})

(defn- signal-text
  "Everything a handler could render from a signal: the message plus every
   field the default console handler prints."
  [signal]
  (str (some-> (:msg_ signal) force) " " (pr-str (dissoc signal :msg_))))

(deftest summarize-renders-shape-not-content
  (testing "resources are reduced to type and id"
    (is (= "Patient/p1" (ftrace/summarize patient)))
    (is (= "Coverage/c1" (ftrace/summarize coverage))))

  (testing "bundles report type and size, never entries"
    (is (= "Bundle/searchset entries=1 total=3"
           (ftrace/summarize {:resourceType "Bundle" :type "searchset"
                              :total 3 :entry [{:resource patient}]}))))

  (testing "collections report size and element type"
    (is (= "[2 Patient]" (ftrace/summarize [patient patient])))
    (is (= "[0 items]" (ftrace/summarize []))))

  (testing "ring responses report status, not body"
    (is (= "#Response[200]" (ftrace/summarize {:status 200 :body patient}))))

  (testing "values that cannot carry PHI in a return position are kept"
    (is (= "nil" (ftrace/summarize nil)))
    (is (true? (ftrace/summarize true)))
    (is (= 42 (ftrace/summarize 42)))
    (is (= :ok (ftrace/summarize :ok))))

  (testing "strings report length, not text"
    (is (= "#String[9]" (ftrace/summarize "Testerson"))))

  (testing "unrecognized values report their class only"
    (is (= "#java.lang.Object" (ftrace/summarize (Object.)))))

  (testing "maps without a resourceType report size only"
    (is (= "#Map[2]" (ftrace/summarize {:tx-id 9 :basis :b})))))

(deftest summarize-does-not-realize-lazy-sequences
  (let [realized (atom 0)
        lazy (map (fn [x] (swap! realized inc) x) [patient patient])]
    (is (= "[? items]" (ftrace/summarize lazy)))
    (is (zero? @realized)
        "summarizing must not force a lazy sequence as a side effect of logging")))

(deftest trace-signal-carries-no-resource-content
  (doseq [[label resource secrets]
          [["Patient" patient ["Testerson" "Alice" "1980-02-03"]]
           ["Coverage" coverage ["ZXQ123456789"]]]]
    (testing (str label " content stays out of the signal")
      (let [signal (t/with-signal
                     (ftrace/trace! {:id :store/read
                                     :data {:resource-type label :id (:id resource)}}
                                    resource))
            text (signal-text signal)]
        (doseq [secret secrets]
          (is (not (str/includes? text secret))
              (str secret " reached the signal: " text)))))))

(deftest trace-returns-the-real-value
  (is (= patient (ftrace/trace! {:id :store/read} patient))
      "redaction applies to the signal only, never to what the store returns")
  ;; Telemere wraps a throwing run form in an ExceptionInfo of its own, both
  ;; before and after redaction; server.middleware/find-fhir-status-ex walks the
  ;; cause chain because of it.
  (let [thrown (try (ftrace/trace! {:id :store/read}
                                   (throw (IllegalStateException. "boom")))
                    (catch Throwable e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown))
    (is (instance? IllegalStateException (.getCause thrown))
        "the original store exception stays reachable on the cause chain")))

(deftest trace-reports-the-callers-location
  ;; Telemere derives :coords from the macro's &form, so a naive wrapper macro
  ;; would report trace.clj's own line for every call site (CLJ-865).
  (let [first-line (-> (t/with-signal (ftrace/trace! {:id :store/read} patient))
                       :coords first)
        next-line (-> (t/with-signal (ftrace/trace! {:id :store/read} patient))
                      :coords first)]
    (is (= "fhir-store.trace-test" (:ns (t/with-signal (ftrace/trace! {:id :x} 1)))))
    (is (some? first-line))
    (is (= 2 (- next-line first-line))
        "coords must track each call site, not a fixed line inside the macro")))

(deftest trace-honours-an-explicit-run-val
  (let [signal (t/with-signal
                 (ftrace/trace! {:id :store/read :run-val "custom"} patient))]
    (is (= "custom" (:run-val signal)))))

(deftest trace-rejects-a-non-literal-options-map
  ;; The :run-val opt is injected at macroexpansion, so a runtime-computed
  ;; options map would silently skip redaction. Fail loudly instead.
  (is (thrown-with-msg?
       Exception #"needs a literal options map|fhir-store\.trace/trace!"
       (macroexpand-1 '(fhir-store.trace/trace! opts-symbol :body)))))

(deftest trace-values-var-restores-full-values
  (binding [ftrace/*trace-values?* true]
    (let [signal (t/with-signal (ftrace/trace! {:id :store/read} patient))]
      (is (= patient (:run-val signal))
          "the development escape hatch still records real values"))))
