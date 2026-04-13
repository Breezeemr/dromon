(ns server.trace-cli
  "Babashka task `bb trace`: wraps curl, injects the X-Dromon-Trace header,
  and renders the captured span tree on stderr while passing the response
  body through to stdout unchanged.

  Usage examples:
    bb trace -s http://localhost:8080/default/fhir/metadata
    bb trace -s http://localhost:8080/default/fhir/Patient/123 | jq .

  When the server has not been started with DROMON_DEV_TRACE_TAP=1, the
  X-Dromon-Trace-Json response header will be absent and a friendly notice
  is printed to stderr -- the response body still flows through to stdout."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.util Base64]
           [java.util.zip GZIPInputStream]
           [java.io ByteArrayInputStream]))

(def ^:private trace-header "X-Dromon-Trace: 1")
(def ^:private trace-resp-header "x-dromon-trace-json")

(defn- term-cols []
  (try
    (let [{:keys [out exit]} @(p/process ["tput" "cols"] {:out :string :err :string})]
      (if (zero? exit)
        (Integer/parseInt (str/trim out))
        100))
    (catch Throwable _ 100)))

(defn- decode-trace-header [^String hval]
  (try
    (let [bytes (.decode (Base64/getDecoder) hval)
          gzin (GZIPInputStream. (ByteArrayInputStream. bytes))
          json-str (slurp gzin)]
      (json/parse-string json-str true))
    (catch Throwable e
      (binding [*out* *err*]
        (println "trace-tap: failed to decode X-Dromon-Trace-Json header:" (ex-message e)))
      nil)))

(defn- run-curl
  "Runs curl with the given args, capturing headers and body separately.
  Returns {:status int :headers {} :body bytes :exit int}."
  [args]
  (let [headers-file (java.io.File/createTempFile "dromon-trace-headers" ".txt")
        body-baos (java.io.ByteArrayOutputStream.)
        cmd (concat ["curl" "-sS" "-D" (.getAbsolutePath headers-file)
                     "-H" trace-header]
                    args)
        proc (p/process cmd {:out :bytes :err :inherit})
        result @proc
        body (:out result)
        header-text (slurp headers-file)
        _ (.delete headers-file)
        header-lines (str/split-lines header-text)
        headers (into {}
                      (keep (fn [line]
                              (when-let [idx (str/index-of line ": ")]
                                [(str/lower-case (subs line 0 idx))
                                 (subs line (+ idx 2))])))
                      header-lines)]
    {:headers headers
     :body body
     :exit (:exit result)}))

(defn- format-duration [nanos]
  (let [ms (/ nanos 1e6)]
    (cond
      (< ms 1)    (format "%.2fms" (double ms))
      (< ms 1000) (format "%.1fms" (double ms))
      :else       (format "%.2fs"  (double (/ ms 1000))))))

(defn- build-tree [spans]
  (let [by-parent (group-by :parentSpanId spans)
        ;; Roots = spans whose parent is missing from the captured set
        ids (set (map :spanId spans))
        roots (remove (fn [s] (contains? ids (:parentSpanId s))) spans)
        sort-fn (fn [xs] (sort-by :startNanos xs))]
    (letfn [(build [s]
              {:span s
               :children (mapv build (sort-fn (get by-parent (:spanId s) [])))})]
      (mapv build (sort-fn roots)))))

(defn- render-tree [nodes max-cols]
  (let [sb (StringBuilder.)]
    (letfn [(emit-line [^String s]
              (let [trimmed (if (> (.length s) max-cols)
                              (str (subs s 0 (max 0 (- max-cols 1))) "…")
                              s)]
                (.append sb trimmed)
                (.append sb "\n")))
            (walk [{:keys [span children]} prefix is-last? root?]
              (let [connector (cond root? ""
                                    is-last? "└── "
                                    :else "├── ")
                    line (str prefix connector
                              (:name span)
                              " "
                              (format-duration (:durationNanos span)))]
                (emit-line line)
                (let [child-prefix (str prefix
                                        (cond root? ""
                                              is-last? "    "
                                              :else "│   "))
                      n (count children)]
                  (doseq [[i child] (map-indexed vector children)]
                    (walk child child-prefix (= i (dec n)) false)))))]
      (let [n (count nodes)]
        (doseq [[i root] (map-indexed vector nodes)]
          (walk root "" (= i (dec n)) true))))
    (.toString sb)))

(defn -main [& argv]
  (let [argv (vec argv)
        help? (or (some #{"--help"} argv) (= ["-h"] argv))]
    (cond
      help?
      (do (binding [*out* *err*]
            (println "Usage: bb trace <curl args>")
            (println "  Wraps curl with X-Dromon-Trace: 1 and renders the captured")
            (println "  span tree on stderr. Body passes through to stdout.")
            (println "  All arguments are forwarded to curl unchanged."))
          (System/exit 0))

      :else
      (let [{:keys [headers body exit]} (run-curl argv)
            ^bytes body-bytes body]
        (when (pos? (alength body-bytes))
          (.write (System/out) body-bytes)
          (.flush (System/out)))
        (.flush (System/out))
        (binding [*out* *err*]
          (if-let [hval (get headers trace-resp-header)]
            (let [{:keys [spans overflow]} (decode-trace-header hval)]
              (cond
                (nil? spans)
                (println "trace-tap: header present but undecodable")

                (empty? spans)
                (println "trace-tap: no spans captured (server side)")

                :else
                (let [tree (build-tree spans)
                      cols (term-cols)
                      rendered (render-tree tree cols)]
                  (println)
                  (print rendered)
                  (.flush *out*)
                  (when overflow
                    (println "(span buffer overflow -- some spans dropped)")))))
            (println "trace-tap: X-Dromon-Trace-Json header missing -- is DROMON_DEV_TRACE_TAP=1 set on the server?"))
          (.flush (System/err)))
        (System/exit (or exit 0))))))
