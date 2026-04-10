(ns com.breezeehr.fhir-shape
  "A lightweight 'shape' representation of FHIR schema structure.
   Tracks field existence, cardinality, type codes, and ref targets
   without building full malli schemas. Used by the form generator
   to make decisions about sequential wrapping, ref threading, etc.

   A shape is a map of {keyword → field-info}. A field-info is a map:
   {:type \"CodeableConcept\" :max \"*\" :seq-field? true :ref? true :ref-kw :ns/Foo}")

(defn field-info
  "Extract shape info for a single field from its FHIR element metadata."
  ([element-type max-val]
   (field-info element-type max-val nil))
  ([element-type max-val ref-kw]
   (let [code (:code element-type)]
     (cond-> {:type code}
       max-val (assoc :max max-val)
       (or (= max-val "*")
           (and max-val (not= max-val "0") (not= max-val "1")))
       (assoc :seq-field? true)
       (#{"BackboneElement" "Element"} code) (assoc :complex? true)
       (and code
            (not (#{"BackboneElement" "Element" "Extension" "Resource"} code))
            (not (Character/isLowerCase (.charAt ^String code 0))))
       (assoc :ref? true)
       ref-kw (assoc :ref-kw ref-kw)))))

(defn seq-field?
  "Is this field sequential (max > 1)?"
  [info]
  (:seq-field? info false))

(defn ref?
  "Is this field a reference to another type?"
  [info]
  (:ref? info false))

(defn complex?
  "Is this a complex type (BackboneElement/Element)?"
  [info]
  (:complex? info false))

(defn sliced?
  "Has this field been converted to a :multi by slicing?"
  [info]
  (:sliced? info false))

(defn content-ref?
  "Is this a contentReference field (recursive/lazy ref to a BackboneElement)?"
  [info]
  (:content-ref? info false))

(defn get-field
  "Get field info from shape, returns nil if not present."
  [shape k]
  (get shape k))

(defn assoc-field
  "Add or update a field in the shape."
  [shape k info]
  (assoc shape k info))

(defn dissoc-field
  "Remove a field from the shape."
  [shape k]
  (dissoc shape k))

(defn mark-sliced
  "Mark a field as having :multi slicing applied."
  [shape k]
  (assoc-in shape [k :sliced?] true))

(defn from-fhir-elements
  "Build a shape from a sequence of FHIR ElementDefinition maps.
   `parent-path` is the path prefix (e.g. [\"Observation\"]).
   Only captures direct children (depth = parent-depth + 1)."
  [elements parent-path]
  (let [parent-depth (count parent-path)]
    (reduce
     (fn [shape {:keys [path type max]}]
       (if (and (vector? path)
                (> (count path) parent-depth)
                (= (inc parent-depth) (count path)))
         (let [field-name (nth path parent-depth)
               k (keyword field-name)
               attr-type (first type)]
           (if attr-type
             (assoc-field shape k (field-info attr-type max))
             shape))
         shape))
     {}
     elements)))
