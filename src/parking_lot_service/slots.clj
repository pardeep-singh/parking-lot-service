(ns parking-lot-service.slots
  (:require [clojure.string :as cs]
            [parking-lot-service.fdb :as fdb]
            [clj-fdb.subspace.subspace :as fsubspace]
            [clj-fdb.tuple.tuple :as ftuple]
            [clj-fdb.core :as fc]
            [byte-streams :as bs]
            [cheshire.core :as cc]
            [clj-fdb.transaction :as ftr]))

(defonce
  ^{:doc "Number of rows in a Parking-lot."}
  rows 5)

(defonce
  ^{:doc "Number of columns in a Parking-lot."}
  columns 5)

(defonce
  ^{:doc "Alphabetical labels. These are used for labelling slots."}
  labels (map (comp str char) (range 65 91)))

(defonce
  ^{:doc "Number of motorcyle slots. Only motorcycle can be parked in these slots."}
  motorcyle-slots 5)

(defonce
  ^{:doc "Number of compact slots. Motorcyle or Car can be parked in these slots."}
  compact-slots 5)

(defonce
  ^{:doc "Number of large slots. Motorcyle/Car/Bus can be parked in these slots.
    In order to Park Bus, 5 free slots of this type should be available in same row."}
  large-slots 5)

(def slot-types
  {"0" "motorcycle"
   "1" "compact"
   "2" "large"})

(def slot-status
  {"0" "available"
   "1" "not_available"})

(def motorcycle-slot-key "0")
(def compact-slot-key "1")
(def large-slot-key "2")
(def available-status-key "0")
(def not-available-status-key "1")

(def parking-lot-subspace (fsubspace/create-subspace (ftuple/from "slots")))
(def slots-info-subspace (fsubspace/create-subspace (ftuple/from "slots_info")))


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
                             (and (= (get k 1) motorcycle-slot-key)
                                  (= (get k 2) available-status-key))
                             (update acc :motorcyle_available_slots conj (last k))

                             (and (= (get k 1) motorcycle-slot-key)
                                  (= (get k 2) not-available-status-key))
                             (update acc :motorcycle_occupied_slots conj (last k))

                             (and (= (get k 1) compact-slot-key)
                                  (= (get k 2) available-status-key))
                             (update acc :compact_available_slots conj (last k))

                             (and (= (get k 1) compact-slot-key)
                                  (= (get k 2) not-available-status-key))
                             (update acc :compact_occupied_slots conj (last k))

                             (and (= (get k 1) large-slot-key)
                                  (= (get k 2) available-status-key))
                             (update acc :large_available_slots conj (last k))

                             (and (= (get k 1) large-slot-key)
                                  (= (get k 2) not-available-status-key))
                             (update acc :large_occupied_slots conj (last k)))))
        grouped-slots (reduce-kv group-slots-fn
                                 {}
                                 slots)]
    {:slots grouped-slots}))


(defn init-parking-lot
  "Initializes the parking-lot space based on the value of `rows` and `columns`(row * columns).
  Assigns slots to each type of slot based on `motorcycle-slot-counts`, `compact-slot-counts` and
  `large-slot-counts`. Slots to each type is assigned in continous manner."
  [fdb-conn rows columns motorcycle-slot-counts compact-slot-counts large-slot-counts]
  {:pre [(= (* rows columns)
            (apply + [motorcycle-slot-counts compact-slot-counts large-slot-counts]))]}
  (let [ms-counts (atom motorcycle-slot-counts)
        cs-counts (atom compact-slot-counts)
        ls-counts (atom large-slot-counts)]
    (for [row-label (take rows labels)
          column-label (take columns labels)]
      (let [slot-type (cond
                        (> @ms-counts 0) (do (swap! ms-counts dec)
                                             motorcycle-slot-key)
                        (> @cs-counts 0) (do (swap! cs-counts dec)
                                             compact-slot-key)
                        (> @ls-counts 0) (do (swap! ls-counts dec)
                                             large-slot-key))
            slot-id (cs/join [row-label "-" column-label])
            slot-subspace (fsubspace/get parking-lot-subspace (ftuple/from slot-type available-status-key))]
        (ftr/run (:fdb-conn fdb-conn)
          (fn [tr]
            (fc/set-subspaced-key tr
                                  slot-subspace
                                  (ftuple/from slot-id)
                                  "1")
            (fc/set-subspaced-key tr
                                  slots-info-subspace
                                  (ftuple/from slot-id)
                                  {:type slot-type
                                   :status available-status-key}
                                  :valfn #(bs/to-byte-array (cc/generate-string %)))))))))


(defn clear-parking-lot
  "Clears `parking-lot-subspace` and `slots-info-subspace`."
  [fdb]
  (fc/clear-subspaced-range (:fdb-conn fdb)
                            parking-lot-subspace)
  (fc/clear-subspaced-range (:fdb-conn fdb)
                            slots-info-subspace))


(defn reset-parking-lot
  "Clears `parking-lot-subspace` and `slots-info-subspace` and initializes the
  parking-lot again. Initializes parking-lot based on `rows` * `columns` value.
  Slots are divided between different types based on `motorcycle-slots`, `compact-slots` and
  `large-slots` values."
  [fdb]
  (clear-parking-lot fdb)
  (init-parking-lot fdb rows columns motorcyle-slots compact-slots large-slots))
