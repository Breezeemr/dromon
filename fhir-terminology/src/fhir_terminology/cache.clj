(ns fhir-terminology.cache
  (:require [fhir-terminology.protocol :as proto]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:import [com.github.benmanes.caffeine.cache Caffeine]
           [java.util.concurrent TimeUnit]))

(defn- build-cache [max-size ttl-minutes]
  (-> (Caffeine/newBuilder)
      (.maximumSize max-size)
      (.expireAfterWrite ttl-minutes TimeUnit/MINUTES)
      (.build)))

(defrecord CachedTerminologyService [delegate expand-cache lookup-cache validate-cache]
  proto/ITerminologyService

  (expand-valueset [_ params]
    (let [cache-key (pr-str (select-keys params [:url :filter :count :offset :context :contextDirection]))
          cached (.getIfPresent expand-cache cache-key)]
      (if cached
        cached
        (let [result (proto/expand-valueset delegate params)]
          (.put expand-cache cache-key result)
          result))))

  (lookup-code [_ params]
    (let [cache-key (pr-str (select-keys params [:system :code :version]))
          cached (.getIfPresent lookup-cache cache-key)]
      (if cached
        cached
        (let [result (proto/lookup-code delegate params)]
          (.put lookup-cache cache-key result)
          result))))

  (validate-code [_ params]
    (let [cache-key (pr-str (select-keys params [:url :system :code :display]))
          cached (.getIfPresent validate-cache cache-key)]
      (if cached
        cached
        (let [result (proto/validate-code delegate params)]
          (.put validate-cache cache-key result)
          result)))))

(defn wrap-with-cache
  "Wraps a terminology service with TTL-based caching."
  [delegate & {:keys [max-size ttl-minutes] :or {max-size 10000 ttl-minutes 60}}]
  (->CachedTerminologyService delegate
                               (build-cache max-size ttl-minutes)
                               (build-cache max-size ttl-minutes)
                               (build-cache max-size ttl-minutes)))

(defmethod ig/init-key :fhir-terminology/cached [_ {:keys [delegate max-size ttl-minutes]}]
  (log/info "Wrapping terminology service with cache, max-size:" (or max-size 10000) "ttl:" (or ttl-minutes 60) "min")
  (wrap-with-cache delegate :max-size (or max-size 10000) :ttl-minutes (or ttl-minutes 60)))
