(ns com.breezeehr.malli-decimal
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [malli.transform :as mt]
            [malli.util :as mu]
            #?@(:cljs [[goog.object]])))

(defn ->bigdec [x]
  (try
    #?(:clj   (bigdec x)
       :cljs (js/Big. x))
    (catch #?(:clj Exception :cljs js/Error) _e x)))

(defn -decimal? [x]
  #?(:clj  (decimal? x)
     :cljs (instance? js/Big x)))
(defn -lte? [x y]
  #?(:clj  (<= x y)
     :cljs (.lte x y)))

(defn scale [x]
  #?(:clj  (-> x .scale)
     :cljs (-> x
               (goog.object/get "c") count
               (- (-> x (goog.object/get "e") inc)))))

(defn last-digit [x]
  (aget x (dec (count x))))

(defn -trailing-zero? [x]
  #?(:clj  (and
            (-> x .scale pos?)
            (-> x .unscaledValue (.mod (biginteger 10)) (= 0)))
     :cljs false
     #_(and
        (-> x scale pos?)
        (-> x digits last-digit (= 0)))))

(def decimal-schemas
  {:decimal (m/-simple-schema {:type    :decimal,
                               :compile (fn [{:keys [min-characters max-characters min max max-decimal-places disallow-trailing-zero]} _ _options]
                                          (when min-characters
                                            (when-not (int? min-characters)
                                              (m/-fail! ::invalid-children {:min-characters min-characters})))
                                          (when max-characters
                                            (when-not (int? max-characters)
                                              (m/-fail! ::invalid-children {:max-characters max-characters})))
                                          (when min
                                            (when-not (-decimal? min)
                                              (m/-fail! ::invalid-children {:min min})))
                                          (when max
                                            (when-not (-decimal? max)
                                              (m/-fail! ::invalid-children {:max max})))
                                          (when max-decimal-places
                                            (when-not (int? max-decimal-places)
                                              (m/-fail! ::invalid-children {:max-decimal-places max-decimal-places})))
                                          {:pred            #(and (-decimal? %)
                                                                  (let [length (count (str %))]
                                                                    (and (if min-characters
                                                                           (<= min-characters length)
                                                                           true)
                                                                         (if max-characters
                                                                           (<= length max-characters)
                                                                           true)))
                                                                  (if min (-lte? min %)
                                                                      true)
                                                                  (if max (-lte? % max)
                                                                      true)
                                                                  (if max-decimal-places
                                                                    (<= (scale %) max-decimal-places)
                                                                    true)
                                                                  (if disallow-trailing-zero
                                                                    (not (-trailing-zero? %))
                                                                    true))
                                           :type-properties {:error/fn
                                                             (fn [{:keys [schema value negated] :as error} _]
                                                               (let [{:keys [min-characters max-characters min max max-decimal-places]} (m/properties schema)
                                                                     length (count (str value))]
                                                                 (cond
                                                                   (not (-decimal? value)) (str value " not decimal")
                                                                   (and min (= min max)) (str value " should be " min)
                                                                   (and min ((if negated >= <) value min)) (str value " should be at least " min)
                                                                   (and max-decimal-places
                                                                        (not (<= (doto (scale value) prn) max-decimal-places)))
                                                                   (str value " should have at most "
                                                                        max-decimal-places
                                                                        " digits to the right of the decimal")
                                                                   (and max ((if negated <= >) value max)) (str value " should be at most " max)
                                                                   (and max-characters
                                                                        (> length max-characters)) (str "Length should be"
                                                                                                        (if min-characters
                                                                                                          (str " between " min-characters " ")
                                                                                                          " ")
                                                                                                        (if max-characters
                                                                                                          (if min-characters
                                                                                                            (str "and " max-characters " ")
                                                                                                            (str "less than " max-characters " "))
                                                                                                          " ")
                                                                                                        "digits long,"
                                                                                                        value " is "
                                                                                                        length " long ")
                                                                   (and min-characters
                                                                        (< length min-characters)) (str "Length should be"
                                                                                                        (if min-characters
                                                                                                          (str " between " min-characters " ")
                                                                                                          " ")
                                                                                                        (if max-characters
                                                                                                          (if min-characters
                                                                                                            (str "and " max-characters " ")
                                                                                                            (str "less than " max-characters " "))
                                                                                                          " ")
                                                                                                        "digits long,"
                                                                                                        value " is "
                                                                                                        length " long ")
                                                                   (and disallow-trailing-zero
                                                                        (-trailing-zero? value))
                                                                   (str value " should not have trailing zeros"))))
                                                             :decode/string ->bigdec
                                                             :encode/string mt/-any->string
                                                             :decode/json   ->bigdec
                                                             ;:encode/json mt/-any->string
                                                             #_#_:json-schema {:type   "decimal"
                                                                               :format "int64"
                                                                               ;:minimum decimal-min
                                                                               ;:maximum decimal-min
                                                                               }}})})})
