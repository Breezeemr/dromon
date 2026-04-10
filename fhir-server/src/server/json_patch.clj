(ns server.json-patch
  "RFC 6902 JSON Patch implementation for FHIR resources.
   Supports operations: add, remove, replace, move, copy, test.")

(defn- parse-path
  "Parse a JSON Pointer (RFC 6901) into a sequence of keys.
   E.g. \"/name/0/family\" -> [\"name\" 0 \"family\"]"
  [path]
  (if (or (nil? path) (= path ""))
    []
    (let [parts (rest (clojure.string/split path #"/"))]
      (mapv (fn [p]
              (let [unescaped (-> p
                                  (clojure.string/replace "~1" "/")
                                  (clojure.string/replace "~0" "~"))]
                (if (re-matches #"\d+" unescaped)
                  (parse-long unescaped)
                  (keyword unescaped))))
            parts))))

(defn- get-at
  "Get the value at a parsed path in a nested structure."
  [doc path]
  (reduce (fn [current key]
            (cond
              (and (vector? current) (int? key)) (nth current key)
              (map? current) (get current key)
              :else (throw (ex-info "Invalid path" {:path path :key key}))))
          doc path))

(defn- set-at
  "Set a value at a parsed path in a nested structure."
  [doc path value]
  (if (empty? path)
    value
    (let [key (first path)
          rest-path (rest path)]
      (if (= 1 (count path))
        (cond
          (and (vector? doc) (= key :-))
          (conj doc value)

          (and (vector? doc) (int? key))
          (into (subvec doc 0 key)
                (cons value (subvec doc key)))

          (map? doc)
          (assoc doc key value)

          :else
          (throw (ex-info "Cannot set at path" {:path path})))
        (cond
          (and (vector? doc) (int? key))
          (assoc doc key (set-at (nth doc key) (vec rest-path) value))

          (map? doc)
          (assoc doc key (set-at (get doc key) (vec rest-path) value))

          :else
          (throw (ex-info "Cannot traverse path" {:path path :key key})))))))

(defn- remove-at
  "Remove the value at a parsed path."
  [doc path]
  (if (empty? path)
    (throw (ex-info "Cannot remove root" {}))
    (let [key (last path)
          parent-path (vec (butlast path))]
      (if (empty? parent-path)
        ;; Removing from root
        (cond
          (and (vector? doc) (int? key))
          (into (subvec doc 0 key) (subvec doc (inc key)))

          (map? doc)
          (dissoc doc key)

          :else
          (throw (ex-info "Cannot remove at path" {:path path})))
        ;; Removing from nested location
        (let [parent (get-at doc parent-path)
              new-parent (cond
                           (and (vector? parent) (int? key))
                           (into (subvec parent 0 key) (subvec parent (inc key)))

                           (map? parent)
                           (dissoc parent key)

                           :else
                           (throw (ex-info "Cannot remove at path" {:path path})))]
          ;; Replace the parent with the modified version
          (set-at doc (vec (concat (butlast parent-path) [(last parent-path)])) new-parent))))))

(defn- apply-op
  "Apply a single JSON Patch operation to a document."
  [doc {:keys [op path value from]}]
  (let [parsed-path (parse-path path)]
    (case op
      "add"
      (set-at doc parsed-path value)

      "remove"
      (remove-at doc parsed-path)

      "replace"
      (let [_ (get-at doc parsed-path)] ;; verify path exists
        (if (empty? parsed-path)
          value
          (let [parent-path (vec (butlast parsed-path))
                key (last parsed-path)]
            (if (empty? parent-path)
              (assoc doc key value)
              (let [parent (get-at doc parent-path)]
                (set-at doc (vec (concat (butlast parent-path) [(last parent-path)]))
                        (assoc parent key value)))))))

      "move"
      (let [from-path (parse-path from)
            val (get-at doc from-path)
            without (remove-at doc from-path)]
        (set-at without parsed-path val))

      "copy"
      (let [from-path (parse-path from)
            val (get-at doc from-path)]
        (set-at doc parsed-path val))

      "test"
      (let [actual (get-at doc parsed-path)]
        (if (= actual value)
          doc
          (throw (ex-info "Test operation failed"
                          {:op "test" :path path :expected value :actual actual}))))

      (throw (ex-info "Unknown patch operation" {:op op})))))

(defn apply-patch
  "Apply a sequence of JSON Patch operations (RFC 6902) to a document.
   Each operation is a map with :op, :path, and optionally :value or :from.
   Throws ex-info on invalid operations or failed test assertions."
  [doc operations]
  (reduce apply-op doc operations))
