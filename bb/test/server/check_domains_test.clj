(ns server.check-domains-test
  "The scan runs against a throwaway repository, so each outcome git grep can
   report (a hit, no hit, a failure) is exercised for real, not mocked."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [server.check-domains :as cd]))

;; Built from parts, like the pattern itself, so this file never matches it.
(def ^:private domain-name (str "bree" "zeehr" "." "com"))
(def ^:private dev-host (str "local" "hydra." domain-name))

(defn- with-repo
  "Calls `f` with the path of a fresh git repository whose index holds `files`
   ({path content}). The files are tracked but not committed, which is all
   `git grep` needs. The repository is deleted afterwards."
  [files f]
  (fs/with-temp-dir [dir {:prefix "check-domains-test"}]
    (let [dir (str dir)]
      (shell {:dir dir :out :string :err :string} "git" "init" "-q")
      (doseq [[path content] files]
        (fs/create-dirs (fs/parent (fs/path dir path)))
        (spit (str (fs/path dir path)) content))
      (shell {:dir dir :out :string :err :string} "git" "add" "--all")
      (f dir))))

(def ^:private host-ns (str "com." "bree" "zeehr.fhir-primitives"))
(def ^:private host-org-link (str "Bree" "zeemr/dromon#141"))
(def ^:private host-system (str "flot" "illa"))
(def ^:private host-ig (str "bree" "ze-ig"))
(def ^:private host-realm (str "pg" "oa"))

(deftest hit-kinds-names-the-domain-and-the-development-hosts
  (testing "the production domain, with any subdomain or scheme"
    (is (contains? (cd/hit-kinds (str "http://" domain-name "/list")) :domain))
    (is (contains? (cd/hit-kinds (str "https://api." domain-name "/fhir")) :domain)))
  (testing "a consumer's development hostname, in any case"
    (is (contains? (cd/hit-kinds (str "https://" dev-host ":4444")) :domain))
    (is (contains? (cd/hit-kinds (str "LOCAL" "AUTH")) :domain))
    (is (contains? (cd/hit-kinds (str "local" "dromon")) :domain)))
  (testing "neutral names are not hits"
    (doseq [text ["https://localhost:4444" "https://fhir.local:8443" "http://example.org/fhir"]]
      (is (= #{} (cd/hit-kinds text)) text))))

(deftest hit-kinds-names-the-host's-systems
  (testing "a host system, package or realm"
    (doseq [text [(str "standing in for " host-system "'s IndexedStore")
                  (str "the " host-ig " malli package")
                  (str "{:object \"" host-realm "/admin\"}")
                  (str "com." "bree" "zehealthplatform.storage/apply-overlay")]]
      (is (= #{:name} (cd/hit-kinds text)) text)))
  (testing "ownership is not implementation: the package prefix and the GitHub org"
    (is (= #{} (cd/hit-kinds (str "(ns " host-ns ")"))))
    (is (= #{} (cd/hit-kinds (str "src/com/" "bree" "zeehr/main.clj"))))
    (is (= #{} (cd/hit-kinds (str "see " host-org-link)))))
  (testing "a host name after the package prefix is still a hit"
    (is (= #{:name} (cd/hit-kinds (str "com." "bree" "zeehr." host-system ".fhir/x")))))
  (testing "whole words only, so ordinary words that contain one are not hits"
    (doseq [text ["mastery of the masthead" "the sails were furled" "a pyramid" "a decanter"
                  "galley proofs" "usepackage"]]
      (is (= #{} (cd/hit-kinds text)) text))))

(deftest scan-reports-a-hit-in-a-tracked-file
  (with-repo {"docs/notes.md" (str "see https://" dev-host ":4444\n")}
    (fn [dir]
      (let [{:keys [status out]} (cd/scan dir)]
        (is (= :hits status))
        (is (str/includes? out "docs/notes.md:1:"))))))

(deftest scan-reports-clean-for-a-neutral-tree
  (with-repo {"a.clj" "(def issuer \"https://localhost:4444/\")\n"}
    (fn [dir]
      (is (= :clean (:status (cd/scan dir)))))))

(deftest scan-ignores-untracked-files
  (with-repo {"a.clj" "(def x 1)\n"}
    (fn [dir]
      (spit (str (fs/path dir "later.md")) (str "https://" domain-name "\n"))
      (is (= :clean (:status (cd/scan dir)))
          "the guard covers the tracked tree, which is what ships"))))

(deftest scan-covers-the-whole-tree-from-a-subdirectory
  (with-repo {"top.md" (str domain-name "\n")
              "sub/ok.md" "nothing\n"}
    (fn [dir]
      (is (= :hits (:status (cd/scan (str (fs/path dir "sub")))))))))

(deftest a-failed-scan-is-an-error-not-a-pass
  (fs/with-temp-dir [dir {:prefix "check-domains-test-nogit"}]
    (is (= :error (:status (cd/scan (str dir)))))))

;; ---------------------------------------------------------------------------
;; Baseline
;; ---------------------------------------------------------------------------

(defn- baseline-file [entries]
  {cd/baseline-path (pr-str entries)})

(deftest a-baselined-line-is-allowed-and-a-new-one-is-not
  (let [line (str "standing in for " host-system "'s store")]
    (with-repo (merge {"a.clj" (str ";; " line "\n")}
                      (baseline-file [["a.clj" (str ";; " line)]]))
      (fn [dir]
        (is (= :clean (:status (cd/scan dir))))))
    (with-repo (merge {"a.clj" (str ";; " line "\n")
                       "b.clj" (str ";; " line "\n")}
                      (baseline-file [["a.clj" (str ";; " line)]]))
      (fn [dir]
        (let [{:keys [status new]} (cd/scan dir)]
          (is (= :hits status))
          (is (= ["b.clj"] (map :path new))
              "an entry covers its own file only"))))))

(deftest each-occurrence-needs-its-own-entry
  (let [line (str ";; " host-system)]
    (with-repo (merge {"a.clj" (str line "\n" line "\n")}
                      (baseline-file [["a.clj" line]]))
      (fn [dir]
        (is (= 1 (count (:new (cd/scan dir)))))))))

(deftest a-domain-hit-is-never-baselined
  (with-repo (merge {"a.md" (str "see https://" dev-host "\n")}
                    (baseline-file [["a.md" (str "see https://" dev-host)]]))
    (fn [dir]
      (is (= :hits (:status (cd/scan dir)))))))

(deftest a-stale-entry-fails-until-pruned
  (with-repo (merge {"a.clj" "(def x 1)\n"}
                    (baseline-file [["a.clj" (str ";; " host-system)]]))
    (fn [dir]
      (let [{:keys [status stale]} (cd/scan dir)]
        (is (= :hits status))
        (is (= [["a.clj" (str ";; " host-system)]] stale)))
      (cd/write-baseline! dir (remove (set (:stale (cd/scan dir)))
                                      (cd/read-baseline dir)))
      (is (= [] (cd/read-baseline dir)))
      (is (= :clean (:status (cd/scan dir)))))))
