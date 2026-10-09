(ns server.check-domains
  "Guards this repository's host neutrality: no tracked file names a production
   domain or a consuming host's development hostnames. dromon is an
   independent FHIR server, so its fixtures, docs and defaults read localhost.

   `bb check-domains` greps the tracked tree (git grep, so ignored and
   untracked files are not looked at) and fails on any hit.

   The pattern is assembled from parts so that this file, the test and the
   bb.edn entry do not match the pattern they enforce."
  (:require [babashka.process :refer [process]]
            [clojure.string :as str]))

(def ^:private forbidden-parts
  [["breezeehr" "\\." "com"]
   ["local" "(hydra|kratos|keto|auth|flotilla|jib3|dromon|newcare|portal|care)"]])

(defn pattern
  "The extended regular expression `git grep -E` is given."
  []
  (str/join "|" (map #(apply str %) forbidden-parts)))

(defn scan
  "Runs `git grep` for [[pattern]] over every tracked text file of the
   repository containing `dir` (default: the working directory), whichever
   subdirectory that is. Returns {:status :clean|:hits|:error, :out s, :err s}.

   git grep exits 0 on a match, 1 on none and above 1 on a failure (not a
   repository, a bad pattern), so the three are told apart rather than read as
   a boolean: treating every non-zero exit as clean would let a broken run
   pass."
  ([] (scan nil))
  ([dir]
   (let [{:keys [exit out err]}
         @(process (cond-> ["git"]
                     dir (into ["-C" dir])
                     :always (into ["grep" "-n" "-i" "-I" "--full-name" "-E" "-e" (pattern) "--" ":/"]))
                   {:out :string :err :string})]
     {:status (case (int exit) 0 :hits 1 :clean :error)
      :out    out
      :err    err})))

(defn -main
  "Prints each hit and returns the process exit code: 0 clean, 1 hits, 2 when
   the scan itself failed."
  [& _]
  (let [{:keys [status out err]} (scan)]
    (case status
      :clean (do (println "check-domains: no hits") 0)
      :hits  (do (println "check-domains: forbidden host names in tracked files:")
                 (println (str/trimr out))
                 1)
      (do (println "check-domains: the scan failed:" (str/trim (str err)))
          2))))
