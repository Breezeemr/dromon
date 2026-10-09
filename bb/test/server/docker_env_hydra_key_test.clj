(ns server.docker-env-hydra-key-test
  "The decision half of pre-creating Hydra's JWT access-token key set. The
   dangerous branch is the no-op: Hydra's POST /admin/keys/{set} ADDS a key to a
   set that exists, so a repeated stack start must not rotate it."
  (:require [clojure.test :refer [deftest is]]
            [server.docker-env :as env]))

(deftest an-existing-key-set-is-left-alone
  (is (= :none (env/key-set-action 200))))

(deftest a-missing-key-set-is-created
  (is (= :create (env/key-set-action 404))))

(deftest anything-else-is-not-acted-on
  (doseq [status [0 400 401 403 500 502 503]]
    (is (= :unknown (env/key-set-action status)) (str status))))

(deftest the-access-token-set-is-the-one-hydra-asks-for
  (is (= "hydra.jwt.access-token" env/hydra-access-token-key-set)))
