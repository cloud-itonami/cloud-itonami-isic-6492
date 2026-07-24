(ns credit.store-numeric-identity-test
  "Regression test for the kotobase-persistence-migration numeric-
  identity-attr fix (docs/adr/0003-kotobase-persistence-migration.md).

  kotobase-server's `do-transact` collides EVERY `:db.unique/identity`
  attribute whose value is a NUMBER (not a string) into entity id \"\"
  (empty string) -- confirmed against production, ADR-2607184000's
  known-issues section (cloud-itonami-isic-5820). `credit.store`'s
  schema marks TWO such attrs identity: `:ledger/seq`, `:disbursement/
  seq` (their values come from `(count ...)`, i.e. plain integers, at
  every call site that used to write them before this migration's
  fix).

  Same approach as `commitledger.store-numeric-identity-test` (sibling
  actor, same fleet): drives the real `append-ledger!`/`commit-record!`
  code paths through a real `store/datomic-store`, then reads the
  persisted datoms directly off `conn` (ground truth -- these methods
  transact directly onto `conn`, assuming a synchronous db-api, and are
  never invoked against the remote/async db-api in this actor's design;
  see `DatomicStore`'s own docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.store :as store]
            [langchain.db :as d]))

(def ^:private identity-seq-attrs #{:ledger/seq :disbursement/seq})

(defn- identity-seq-datom-values [s]
  (->> (d/datoms (d/db (:conn s)) :eavt)
       (filter (fn [[_e a _v]] (contains? identity-seq-attrs a)))
       (mapv (fn [[_e a v]] [a v]))))

(defn- assert-no-numeric-identity! [s]
  (let [pairs (identity-seq-datom-values s)]
    (is (seq pairs) "sanity: at least one identity-seq datom should exist by this point")
    (doseq [[attr v] pairs]
      (is (string? v)
          (str attr " must be a STRING (kotobase-server collides numeric "
               "identity-attr values to entity id \"\") -- got " (pr-str v)
               " (" (type v) ")")))))

(deftest append-ledger!-never-sends-a-numeric-ledger-seq
  (let [s (store/datomic-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (store/append-ledger! s {:op :c :disposition :commit})
    (assert-no-numeric-identity! s)
    (testing "and the ledger itself still reads back correctly, in order, despite the string encoding"
      (is (= [:commit :hold :commit] (mapv :disposition (store/ledger s)))))))

(def ^:private app-a
  {:id "app-a" :applicant "A" :requested-amount 500000
   :annual-income 4000000 :existing-debt 500000 :credit-score 720
   :jurisdiction "JPN" :status :approved})

(def ^:private app-b
  {:id "app-b" :applicant "B" :requested-amount 100000
   :annual-income 3000000 :existing-debt 200000 :credit-score 680
   :jurisdiction "ATL" :status :approved})

(deftest loan-mark-disbursed-never-sends-a-numeric-disbursement-seq
  (let [s (store/datomic-store)]
    (store/with-applications s {"app-a" app-a})
    (store/commit-record! s {:effect :loan/mark-disbursed :path ["app-a"]})
    (assert-no-numeric-identity! s)))

;; Direct proof the fix actually prevents the collision, not just that
;; the value LOOKS like a string -- see `commitledger.store-numeric-
;; identity-test`'s parallel comment (same fleet, same reasoning) for
;; the full explanation of what this test does and does not prove.
(deftest a-second-disbursement-in-a-different-jurisdiction-does-not-collide
  (let [s (store/datomic-store)]
    (store/with-applications s {"app-a" app-a "app-b" app-b})
    (store/commit-record! s {:effect :loan/mark-disbursed :path ["app-a"]})
    (store/commit-record! s {:effect :loan/mark-disbursed :path ["app-b"]})
    (assert-no-numeric-identity! s)
    (let [history (store/disbursement-history s)
          rec-a (first history)
          rec-b (second history)]
      (is (= 2 (count history)) "both disbursement records are distinct entities, not collided into one")
      (is (not= (get rec-a "record_id") (get rec-b "record_id"))))))
