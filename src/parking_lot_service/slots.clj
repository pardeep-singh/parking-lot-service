(ns parking-lot-service.slots
  (:require [clojure.string :as cs]
            [parking-lot-service.fdb :as fdb]
            [clj-fdb.subspace.subspace :as fsubspace]
            [clj-fdb.tuple.tuple :as ftuple]
            [clj-fdb.core :as fc]
            [byte-streams :as bs]
            [cheshire.core :as cc]
            [clj-fdb.transaction :as ftr]
            [clojure.set :refer [map-invert]]
            [clj-time.core :as ct]
            [clj-time.coerce :as ctc]))

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
  ^{:doc "Number of motorcycle slots. Only motorcycle can be parked in these slots."}
  motorcycle-slots 5)

(defonce
  ^{:doc "Number of compact slots. Motorcycle or Car can be parked in these slots."}
  compact-slots 5)

(defonce
  ^{:doc "Number of large slots. Motorcycle/Car/Bus can be parked in these slots.
    In order to Park Bus, 5 free slots of this type should be available in same row."}
  large-slots 15)

(def slot-types
  {"0" "motorcycle"
   "1" "compact"
   "2" "large"})

(def slot-status
  {"0" "available"
   "1" "unavailable"})

(def motorcycle-slot-key "0")
(def compact-slot-key "1")
(def large-slot-key "2")
(def available-status-key "0")
(def unavailable-status-key "1")

(def parking-lot-subspace (fsubspace/create-subspace (ftuple/from "slots")))
(def slots-info-subspace (fsubspace/create-subspace (ftuple/from "slots_info")))

(def next-available-slot-order
  {"0" "1"
   "1" "2"})

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
            slot-id (cs/join [row-label column-label])
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
  (init-parking-lot fdb rows columns motorcycle-slots compact-slots large-slots))


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
                             (update acc :motorcycle_available_slots conj (last k))

                             (and (= (get k 1) motorcycle-slot-key)
                                  (= (get k 2) unavailable-status-key))
                             (update acc :motorcycle_unavailable_slots conj (last k))

                             (and (= (get k 1) compact-slot-key)
                                  (= (get k 2) available-status-key))
                             (update acc :compact_available_slots conj (last k))

                             (and (= (get k 1) compact-slot-key)
                                  (= (get k 2) unavailable-status-key))
                             (update acc :compact_unavailable_slots conj (last k))

                             (and (= (get k 1) large-slot-key)
                                  (= (get k 2) available-status-key))
                             (update acc :large_available_slots conj (last k))

                             (and (= (get k 1) large-slot-key)
                                  (= (get k 2) unavailable-status-key))
                             (update acc :large_unavailable_slots conj (last k)))))
        grouped-slots (reduce-kv group-slots-fn
                                 {:motorcycle_available_slots []
                                  :motorcycle_unavailable_slots []
                                  :compact_available_slots []
                                  :compact_unavailable_slots []
                                  :large_available_slots []
                                  :large_unavailable_slots []}
                                 slots)]
    {:slots grouped-slots}))


(defn get-slot
  "Returns a slot massaged object given a slot-id."
  [fdb slot-id]
  (let [slot-info (fc/get-subspaced-key (:fdb-conn fdb)
                                        slots-info-subspace
                                        (ftuple/from slot-id)
                                        :valfn #(cc/parse-string (bs/convert % String) true))
        massaged-slot (-> slot-info
                          (assoc :id slot-id)
                          (update :type slot-types)
                          (update :status slot-status)
                          (update :parked_vehicle_type slot-types))]
    {:slot massaged-slot}))


;; TODO: Extend this function to only park bus if 5 slots are available in same row
(defn get-available-slot-id
  "Given a slot-key, returns the next available slot-id.
  If slot is not available in given slot-key, tries to find an available
  slot based on `next-available-slot-order`."
  [transaction slot-key]
  (let [subspace-key (fsubspace/get parking-lot-subspace (ftuple/from slot-key available-status-key))
        available-slots (fc/get-subspaced-range transaction
                                                subspace-key
                                                (ftuple/from)
                                                :keyfn (comp last ftuple/get-items ftuple/from-bytes))
        first-available-slot (first (keys available-slots))]
    (if (seq first-available-slot)
      {:slot-key slot-key
       :available-slot-id first-available-slot}
      (when (next-available-slot-order slot-key)
        (get-available-slot-id transaction (next-available-slot-order slot-key))))))


;; get available slot-id
;; unset the available slot-id from available-status subspace
;; set the slot-id in unavailable-statis subspace
;; update the slot info in slot-info subspace, add the vehicle-type parked and parking ts
;; return the booked slot-id
(defn park-vehicle
  [fdb {:keys [slot_type]}]
  (let [available-slot-id (ftr/run (:fdb-conn fdb)
                            (fn [tr]
                              (let [slot-key (-> slot-types
                                                 map-invert
                                                 (get slot_type))
                                    {:keys [slot-key available-slot-id]} (get-available-slot-id tr slot-key)]
                                (when available-slot-id
                                  (fc/clear-subspaced-key tr
                                                          (fsubspace/get parking-lot-subspace
                                                                         (ftuple/from slot-key available-status-key))
                                                          (ftuple/from available-slot-id))
                                  (fc/set-subspaced-key tr
                                                        (fsubspace/get parking-lot-subspace
                                                                       (ftuple/from slot-key unavailable-status-key))
                                                        (ftuple/from available-slot-id)
                                                        "1")
                                  (let [slot-info (fc/get-subspaced-key tr
                                                                        slots-info-subspace
                                                                        (ftuple/from available-slot-id)
                                                                        :valfn #(cc/parse-string (bs/convert % String) true))
                                        updated-slot-info (assoc slot-info
                                                                 :parked_vehicle_type slot-key
                                                                 :parking_start_ts (ctc/to-long (ct/now))
                                                                 :status (slot-status unavailable-status-key))]
                                    (fc/set-subspaced-key tr
                                                          slots-info-subspace
                                                          (ftuple/from available-slot-id)
                                                          updated-slot-info
                                                          :valfn #(bs/to-byte-array (cc/generate-string %))))
                                  available-slot-id))))]
    (if available-slot-id
      {:slot_id available-slot-id}
      {:message (format "No available slot for %s slot type." slot_type)})))
