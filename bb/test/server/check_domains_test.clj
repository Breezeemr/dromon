(ns server.check-domains-test
  "The scan runs against a throwaway repository, so each outcome git grep can
   report (a hit, no hit, a failure) is exercised for real, not mocked."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [server.check-domains :as cd]))

;; Built from parts, like the pattern itself, so this file never matches it.
(def ^:private domain-name (str "breezeehr" "." "com"))
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

(deftest pattern-names-the-domain-and-the-development-hosts
  (let [re (re-pattern (str "(?i)" (cd/pattern)))]
    (testing "the production domain, with any subdomain or scheme"
      (is (re-find re (str "http://" domain-name "/list")))
      (is (re-find re (str "https://api." domain-name "/fhir"))))
    (testing "a consumer's development hostname, in any case"
      (is (re-find re (str "https://" dev-host ":4444")))
      (is (re-find re (str "LOCAL" "AUTH")))
      (is (re-find re (str "local" "dromon"))))
    (testing "neutral names are not hits"
      (is (not (re-find re "https://localhost:4444")))
      (is (not (re-find re "https://fhir.local:8443")))
      (is (not (re-find re "http://example.org/fhir")))
      (is (not (re-find re "com.breezeehr.purser"))))))

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
