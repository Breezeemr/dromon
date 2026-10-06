(ns server.json-patch
  "RFC 6902 JSON Patch implementation for FHIR resources.
   Supports operations: add, remove, replace, move, copy, test."
  (:require [clojure.string]))

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

(defn- path-not-found [path]
  (ex-info (str "Path not found: " path) {:path path}))

(defn- element-index
  "The array position `segment` names in `array`, or nil when it names none.
   `limit` is the largest position allowed: the last element for an existing
   target, one past it where `add` inserts."
  [array segment limit]
  (when (and (vector? array) (int? segment) (<= 0 segment limit))
    segment))

(defn- has-target?
  "Whether `segment` names an existing member of `container`."
  [container segment]
  (cond
    (vector? container) (some? (element-index container segment (dec (count container))))
    (map? container) (contains? container segment)
    :else false))

(defn- get-at
  "The value at a parsed path. Throws when the path names nothing: the
   target of remove, replace, move, copy and test must exist (RFC 6902
   section 4)."
  [doc parsed-path path]
  (reduce (fn [current segment]
            (if (has-target? current segment)
              (get current segment)
              (throw (path-not-found path))))
          doc parsed-path))

(defn- update-container
  "`doc` with the container holding the last segment of `parsed-path`
   replaced by `(f container segment)`. The containers above it are rewritten
   in place, an array element by assoc, so `f` alone decides whether the
   operation adds, removes or replaces."
  [doc parsed-path path f]
  (let [[segment & more] parsed-path]
    (cond
      (empty? more)
      (f doc segment)

      (has-target? doc segment)
      (assoc doc segment (update-container (get doc segment) more path f))

      :else
      (throw (path-not-found path)))))

(defn- add-at
  "RFC 6902 section 4.1: insert into an array at an index, or append at `-`;
   set an object member, replacing one that exists."
  [doc parsed-path path value]
  (if (empty? parsed-path)
    value
    (update-container
      doc parsed-path path
      (fn [container segment]
        (cond
          (and (vector? container) (= :- segment))
          (conj container value)

          (vector? container)
          (if-some [i (element-index container segment (count container))]
            (into (conj (subvec container 0 i) value) (subvec container i))
            (throw (path-not-found path)))

          (map? container)
          (assoc container segment value)

          :else
          (throw (path-not-found path)))))))

(defn- remove-at
  "RFC 6902 section 4.2: drop an existing array element or object member."
  [doc parsed-path path]
  (when (empty? parsed-path)
    (throw (ex-info "Cannot remove the whole document" {:path path})))
  (update-container
    doc parsed-path path
    (fn [container segment]
      (cond
        (not (has-target? container segment))
        (throw (path-not-found path))

        (vector? container)
        (into (subvec container 0 segment) (subvec container (inc segment)))

        :else
        (dissoc container segment)))))

(defn- replace-at
  "RFC 6902 section 4.3: the value at an existing location, swapped in place."
  [doc parsed-path path value]
  (if (empty? parsed-path)
    value
    (update-container
      doc parsed-path path
      (fn [container segment]
        (if (has-target? container segment)
          (assoc container segment value)
          (throw (path-not-found path)))))))

(defn- decimal-value
  "A number's value as a BigDecimal, or nil for a NaN or infinite double,
   which has none. `bigdec` reads a double through its shortest decimal
   form, so the double 72.5 becomes 72.5M rather than its exact binary
   expansion."
  ^BigDecimal [x]
  (when-not (and (float? x) (or (NaN? x) (infinite? x)))
    (bigdec x)))

(defn- numerically-equal? [a b]
  (let [a-value (decimal-value a)
        b-value (decimal-value b)]
    (if (and a-value b-value)
      (zero? (.compareTo a-value b-value))
      (= a b))))

(defn- json-equal?
  "Equality for the `test` operation, per RFC 6902 section 4.6.

   Clojure `=` keeps Long, Double and BigDecimal apart, but the two sides of
   a test rarely share a representation: a PATCH body decodes a JSON number
   as an Integer, Long or BigDecimal, while a store reads a FHIR decimal back
   as a BigDecimal. Numbers therefore compare by value, so 72, 72.0 and
   72.00M are equal, inside objects and arrays too. Every other value
   compares with `=`.

   A string never equals a number, even one holding the same digits: the RFC
   requires both values to have the same JSON type, and `test` does not know
   the schema, so it cannot tell a decimal position from a string one. A
   client that keeps decimals as strings must send a test value as a JSON
   number."
  [a b]
  (cond
    (and (number? a) (number? b))
    (numerically-equal? a b)

    (and (map? a) (map? b))
    (and (= (count a) (count b))
         (every? (fn [[k v]] (and (contains? b k) (json-equal? v (get b k)))) a))

    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b))
         (every? true? (map json-equal? a b)))

    :else
    (= a b)))

(defn- apply-op
  "Apply a single JSON Patch operation to a document."
  [doc {:keys [op path value from]}]
  (let [parsed-path (parse-path path)]
    (case op
      "add"
      (add-at doc parsed-path path value)

      "remove"
      (remove-at doc parsed-path path)

      "replace"
      (replace-at doc parsed-path path value)

      "move"
      (let [from-path (parse-path from)
            moved (get-at doc from-path from)]
        (add-at (remove-at doc from-path from) parsed-path path moved))

      "copy"
      (add-at doc parsed-path path (get-at doc (parse-path from) from))

      "test"
      ;; A target that is gone fails the test like one that changed: either
      ;; way the resource is not the one the client read.
      (let [actual (try
                     (get-at doc parsed-path path)
                     (catch clojure.lang.ExceptionInfo _
                       ::absent))]
        (if (and (not= ::absent actual) (json-equal? actual value))
          doc
          (throw (ex-info "Test operation failed"
                          (cond-> {:op "test" :path path :expected value}
                            (not= ::absent actual) (assoc :actual actual))))))

      (throw (ex-info "Unknown patch operation" {:op op})))))

(defn apply-patch
  "Apply a sequence of JSON Patch operations (RFC 6902) to a document.
   Each operation is a map with :op, :path, and optionally :value or :from.
   Throws ex-info on invalid operations or failed test assertions."
  [doc operations]
  (reduce apply-op doc operations))
