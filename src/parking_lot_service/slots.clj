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

(def vehicle-types->slot-key
  {"motorcycle" "motorcycle"
   "car" "compact"
   "bus" "large"})

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

(defonce required-slots-counts
  {motorcycle-slot-key 1
   compact-slot-key 1
   large-slot-key 5})


(defn init-parking-lot
  "Initializes the parking-lot space based on the value of rows and columns(row * columns).
  Assigns slots to each type of slot based on motorcycle-slot-counts, compact-slot-counts and
  large-slot-counts. Slots to each type is assigned in continous manner."
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
  parking-lot again. Initializes parking-lot based on rows and columns value.
  Slots are divided between different types based on `motorcycle-slots`, `compact-slots` and
  `large-slots` values."
  [fdb]
  (clear-parking-lot fdb)
  (init-parking-lot fdb rows columns motorcycle-slots compact-slots large-slots))


(defn get-slots
  "Returns a grouped slots based on type and status of slot."
  [fdb-conn]
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
  {:pre [(seq slot-id)]}
  (let [slot-info (fc/get-subspaced-key (:fdb-conn fdb)
                                        slots-info-subspace
                                        (ftuple/from slot-id)
                                        :valfn #(cc/parse-string (bs/convert % String) true))
        massaged-slot (-> slot-info
                          (assoc :id slot-id)
                          (update :type slot-types)
                          (update :status slot-status))]
    (if (seq slot-info)
      (-> {:slot slot-info}
          (assoc-in [:slot :id] slot-id)
          (update-in [:slot :type] slot-types)
          (update-in [:slot :status] slot-status))
      (throw (ex-info (format "Slot not found for %s slot-id" slot-id)
                      {:msg (format "Slot not found for %s slot-id" slot-id)
                       :type :not-found})))))


(defn mark-slot-as-unavailable!
  "Moves slot-id from available to unavailable subspace."
  [tr slot-id slot-key]
  (fc/clear-subspaced-key tr
                          (fsubspace/get parking-lot-subspace
                                         (ftuple/from slot-key available-status-key))
                          (ftuple/from slot-id))
  (fc/set-subspaced-key tr
                        (fsubspace/get parking-lot-subspace
                                       (ftuple/from slot-key unavailable-status-key))
                        (ftuple/from slot-id)
                        "1"))


(defn update-slot-info-with-vehicle-info!
  "Updates slot-info with vehicle info for given slot-id."
  [tr slot-key vehicle-type slot-id allotted-slots]
  (let [slot-info (fc/get-subspaced-key tr
                                        slots-info-subspace
                                        (ftuple/from slot-id)
                                        :valfn #(cc/parse-string (bs/convert % String) true))
        updated-slot-info (assoc slot-info
                                 :vehicle_type vehicle-type
                                 :parking_start_ts (ctc/to-long (ct/now))
                                 :status unavailable-status-key
                                 :allotted_slots allotted-slots)]
    (fc/set-subspaced-key tr
                          slots-info-subspace
                          (ftuple/from slot-id)
                          updated-slot-info
                          :valfn #(bs/to-byte-array (cc/generate-string %)))))


(defn get-available-row-for-bus-parking
  "Given a list of available slot keys, finds a row where
  Bus can be parked. Bus can only be parked if 5 consective slots are available in
  a row."
  [available-slot-keys]
  (->> available-slot-keys
       (group-by (fn [label]
                   (first label)))
       vals
       (filter (fn [labels]
                 (= (count labels)
                    (get required-slots-counts large-slot-key))))
       first
       sort))


(defn get-available-slots-ids
  "Given a slot-key, returns the next available list of slot-ids.
  If slots are not available in given slot-key, tries to find an available
  slots based on `next-available-slot-order`.
  Returns list of slot-ids based on the `required-slots-counts` to park a vehicle."
  [tr base-slot-key slot-key]
  (let [subspace-key (fsubspace/get parking-lot-subspace (ftuple/from slot-key available-status-key))
        available-slots (fc/get-subspaced-range tr
                                                subspace-key
                                                (ftuple/from)
                                                :keyfn (comp last ftuple/get-items ftuple/from-bytes))
        allotted-slots (if (= base-slot-key large-slot-key)
                         (->> available-slots
                              keys
                              get-available-row-for-bus-parking
                              (take (get required-slots-counts large-slot-key)))
                         (->> available-slots
                              keys
                              sort
                              (take (get required-slots-counts base-slot-key))))]
    (if (seq allotted-slots)
      {:slot-key slot-key
       :allotted-slots allotted-slots}
      (when (next-available-slot-order slot-key)
        (get-available-slots-ids tr base-slot-key (next-available-slot-order slot-key))))))


(defn wrapped-park-vehicle-tr
  "Given a slot-type and vehicle-type, returns a wrapped transaction which performs side-effects for
  parking a vehicle.

  Summary of side effects:
  1. Clears the subspaced key from `available-status-key` subspace.
  2. Sets the subspaced key in `unavailable-status-key` subspace.
  3. Updates the slot info. in `slots-info-subspace`.

  Note: All operations should either succeed or failure together. Partial
  operation will leave parking-lot in a inconsistent state."
  [slot-type vehicle-type]
  (fn [tr]
    (let [slot-key (-> slot-types
                       map-invert
                       (get slot-type))
          {:keys [slot-key allotted-slots]} (get-available-slots-ids tr slot-key slot-key)]
      (when (seq allotted-slots)
        (doseq [slot-id allotted-slots]
          (mark-slot-as-unavailable! tr slot-id slot-key))
        (update-slot-info-with-vehicle-info! tr slot-key vehicle-type (first allotted-slots) allotted-slots)
        (first allotted-slots)))))


(defn park-vehicle
  "Parks a given vehicle and returns the slot_id if available slot is found
  otherwise returns an error message if no free slot is found."
  [fdb {:keys [vehicle_type]}]
  {:pre [(seq vehicle_type)
         ((set (keys vehicle-types->slot-key)) vehicle_type)]}
  (let [slot-type (get vehicle-types->slot-key vehicle_type)
        parked-slot-id (ftr/run (:fdb-conn fdb) (wrapped-park-vehicle-tr slot-type vehicle_type))]
    (if parked-slot-id
      {:slot_id parked-slot-id}
      (throw (ex-info (format "No available slot for %s" vehicle_type)
                      {:msg (format "No available slot for %s" vehicle_type)
                       :type :not-found})))))


(defn mark-slot-as-available!
  "Moves slot-id from unavailable to available subspace."
  [tr slot-id slot-key]
  (fc/clear-subspaced-key tr
                          (fsubspace/get parking-lot-subspace
                                         (ftuple/from slot-key unavailable-status-key))
                          (ftuple/from slot-id))
  (fc/set-subspaced-key tr
                        (fsubspace/get parking-lot-subspace
                                       (ftuple/from slot-key available-status-key))
                        (ftuple/from slot-id)
                        "1"))


(defn wrapped-unpark-vehicle-tr
  "Returns wrapped transaction which is used to unpark a vehicle.

  - Moves allotted slot-ids from unavailable to available subspace.
  - Update slot-info by removing parking info keys.
  - Calculates parking charges based using current time and parking_start_ts stored in slot-info."
  [slot-id]
  (fn [tr]
    (let [slot-info (fc/get-subspaced-key tr
                                          slots-info-subspace
                                          (ftuple/from slot-id)
                                          :valfn #(cc/parse-string (bs/convert % String) true))]
      (when (= (:status slot-info)
               unavailable-status-key)
        (doseq [slot-id (:allotted_slots slot-info)]
          (mark-slot-as-available! tr slot-id (:type slot-info)))
        (fc/set-subspaced-key tr
                              slots-info-subspace
                              (ftuple/from slot-id)
                              (-> slot-info
                                  (select-keys [:type :status])
                                  (assoc :status available-status-key))
                              :valfn #(bs/to-byte-array (cc/generate-string %)))
        (when (:parking_start_ts slot-info)
          (-> (:parking_start_ts slot-info)
              ctc/from-long
              ct/mins-ago))))))


(defn unpark-vehicle
  "Unparks the vehicle and returns the amount if given slot-id was unavailable."
  [fdb {:keys [slot_id]}]
  {:pre [(seq slot_id)]}
  (let [parking-charges (ftr/run (:fdb-conn fdb) (wrapped-unpark-vehicle-tr slot_id))]
    (if parking-charges
      {:charges parking-charges}
      (throw (ex-info (format "Slot not found for %s slot-id" slot_id)
                      {:msg (format "Slot not found for %s slot-id" slot_id)
                       :type :not-found})))))
