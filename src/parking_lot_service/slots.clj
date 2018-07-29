(ns parking-lot-service.slots
  (:require [clojure.string :as cs]
            [parking-lot-service.fdb :as fdb]
            [clj-fdb.subspace.subspace :as fsubspace]
            [clj-fdb.tuple.tuple :as ftuple]
            [clj-fdb.core :as fc]
            [byte-streams :as bs]
            [cheshire.core :as cc]
            [clj-fdb.transaction :as ftr]))


(defonce slot-types
  {0 "2 Wheeler"
   1 "4 Wheeler"})

(defonce slot-status
  {0 "Available"
   1 "Not Available"})


(def label ["A" "B" "C" "D"])


(defonce parking-lot-subspace (fsubspace/create-subspace (ftuple/from "slots")))
(def two-wheeler-subspace (fsubspace/get parking-lot-subspace (ftuple/from "0")))
(def four-wheeler-subspace (fsubspace/get parking-lot-subspace (ftuple/from "1")))


(defn get-slots
  [fdb-conn zmap]
  (let [two-wheeler-slots (fc/get-subspaced-range (:fdb-conn fdb-conn)
                                                  two-wheeler-subspace
                                                  (ftuple/from)
                                                  :keyfn (comp ftuple/get-items ftuple/from-bytes)
                                                  :valfn #(cc/parse-string (bs/convert % String) true))
        four-wheeler-slots (fc/get-subspaced-range (:fdb-conn fdb-conn)
                                                   four-wheeler-subspace
                                                   (ftuple/from)
                                                   :keyfn (comp ftuple/get-items ftuple/from-bytes)
                                                   :valfn #(cc/parse-string (bs/convert % String) true))]
    {:slots {:two_wheeler_slots (vals two-wheeler-slots)
             :four_wheeler_slots (vals four-wheeler-slots)}}))


(defn init-parking-lot
  [fdb-conn rows columns]
  (for [x (take rows label)
        y (take columns label)]
    (let [vehicle-type (rand-int 2)
          vehicle-subspace (if (= vehicle-type 0)
                             (fsubspace/get two-wheeler-subspace (ftuple/from "0"))
                             (fsubspace/get four-wheeler-subspace (ftuple/from "0")))
          slot-id (cs/join [x y])]
      (ftr/run (:fdb-conn fdb-conn)
        (fn [tr]
          (fc/set-subspaced-key tr
                                vehicle-subspace
                                (ftuple/from slot-id)
                                {:id slot-id :type vehicle-type :status 0}
                                :valfn #(bs/to-byte-array (cc/generate-string %)))
          (fc/set-subspaced-key tr
                                parking-lot-subspace
                                (ftuple/from slot-id)
                                {:type vehicle-type :status 0}
                                :valfn #(bs/to-byte-array (cc/generate-string %))))))))
