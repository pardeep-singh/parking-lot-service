(ns parking-lot-service.slots
  (:require [clojure.string :as cs]
            [parking-lot-service.fdb :as fdb]
            [clj-fdb.subspace.subspace :as fsubspace]
            [clj-fdb.tuple.tuple :as ftuple]
            [clj-fdb.core :as fc]
            [byte-streams :as bs]
            [cheshire.core :as cc]
            [clj-fdb.transaction :as ftr]))


(def slot-types
  {"0" "two_wheeler"
   "1" "four_wheeler"})

(def slot-status
  {"0" "available"
   "1" "not_available"})

(defonce two-wheeler-key "0")
(defonce four-wheeler-key "1")
(defonce available-status-key "0")
(defonce not-available-status-key "0")

(def label ["A" "B" "C" "D"])

(defonce parking-lot-subspace (fsubspace/create-subspace (ftuple/from "slots")))
(defonce slots-info-subspace (fsubspace/create-subspace (ftuple/from "slots_info")))
(def two-wheeler-subspace (fsubspace/get parking-lot-subspace (ftuple/from two-wheeler-key)))
(def four-wheeler-subspace (fsubspace/get parking-lot-subspace (ftuple/from four-wheeler-key)))


(defn get-slots
  "Returns a grouped slots based on type and status of slot."
  [fdb-conn zmap]
  (let [slots (fc/get-subspaced-range (:fdb-conn fdb-conn)
                                      parking-lot-subspace
                                      (ftuple/from)
                                      :keyfn (comp ftuple/get-items ftuple/from-bytes)
                                      :valfn #(bs/convert % String))
        group-slots-fn (fn [acc k v]
                         (let [k (vec k)]
                           (cond
                             (and (= (get k 1) two-wheeler-key)
                                  (= (get k 2) available-status-key))
                             (update acc :two_wheeler_available_slots conj (last k))

                             (and (= (get k 1) two-wheeler-key)
                                  (= (get k 2) not-available-status-key))
                             (update acc :two_wheeler_occurpied_slots conj (last k))

                             (and (= (get k 1) four-wheeler-key)
                                  (= (get k 2) available-status-key))
                             (update acc :four_wheeler_available_slots conj (last k))

                             (and (= (get k 1) four-wheeler-key)
                                  (= (get k 2) not-available-status-key))
                             (update acc :four_wheeler_occupied_slots conj (last k)))))
        grouped-slots (reduce-kv group-slots-fn
                                 {}
                                 slots)]
    {:slots grouped-slots}))


(defn init-parking-lot
  "Initializes the parking lot space based on the value of `rows` and `columns`.
  Alternatively assign slots to both `two-wheeler-subspace` and `four-wheeler-subspace`.
  Sets subspaced keys in `two-wheeler-subspace` or `four-wheeler-subspace` based on `status` flag
  also sets slot info in `slots-info-subspace`."
  [fdb-conn rows columns]
  (let [status (atom true)]
    (for [x (take rows label)
          y (take columns label)]
      (let [vehicle-type (if @status
                           two-wheeler-key
                           four-wheeler-key)
            vehicle-subspace (if (= vehicle-type two-wheeler-key)
                               (fsubspace/get two-wheeler-subspace (ftuple/from available-status-key))
                               (fsubspace/get four-wheeler-subspace (ftuple/from available-status-key)))
            slot-id (cs/join [x y])]
        (swap! status not)
        (ftr/run (:fdb-conn fdb-conn)
          (fn [tr]
            (fc/set-subspaced-key tr
                                  vehicle-subspace
                                  (ftuple/from slot-id)
                                  "1")
            (fc/set-subspaced-key tr
                                  slots-info-subspace
                                  (ftuple/from slot-id)
                                  {:type vehicle-type
                                   :status available-status-key}
                                  :valfn #(bs/to-byte-array (cc/generate-string %)))))))))


(defn reset-parking-lot
  "Clears `parking-lot-subspace` and `slots-info-subspace` and initializes the
  parking lot again."
  [fdb]
  (fc/clear-subspaced-range (:fdb-conn fdb)
                            parking-lot-subspace)
  (fc/clear-subspaced-range (:fdb-conn fdb)
                            slots-info-subspace)
  (init-parking-lot fdb 2 2))
