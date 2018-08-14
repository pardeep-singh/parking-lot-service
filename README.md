# Parking lot API service written in Clojure using FoundationDB

Parking lot service which exposes APIs to park/unpark a vehicle.

Parking lot is divided into three type of slots:

- Motorcycle
- Compact (can be used for both motorcycle and car)
- Large (can be used for motorcycle, car and bus)

## Parking Rules

- Parking Motorcycle and Car requires only 1 slot whereas Bus need a 5 consective slots in same row.
- For parking Motorcycle, if slot is not available in motorcycle slot, it can be parked in compact/large space depanding upon the availability.
- For parking car, if slot is not available in compact slot, it can be parked in large space depanding upon the availability.
- Bus can only be parked in large space.

## APIs notes

- `GET /slots` Returns a list of slots categorised by slot type and status.
- `GET /slots/:id` Returns slot information like slot type, status, parked vehicle type, allotted slots, parking timestamp for given slot-id.
- `POST /slots/park` Accepts a `vehicle_type` param and returns a `slot_id` if vehicle is parked successfully.
- `POST /slots/unpark` Accepts a `slot_id` param and returns a `charges` value as parking cost to be charged from customer.

## Prerequisites

You will need [Java 8](https://docs.oracle.com/javase/8/docs/technotes/guides/install/install_overview.html), [Leiningen](https://leiningen.org/) and
[FoundationDB](https://apple.github.io/foundationdb/getting-started-mac.html) to run this project.

This project depands upon [clj_fdb](https://github.com/vedang/clj_fdb) which is a Clojure library used for interacting with FoundationDB Server.
Follow the instructions on library Github page to install this library locally.

## Running

### Using Lein

Initialize the Parking lot Space
```
lein run -m parking-lot-service.slots
```
To start a web server for the application, run:
```
lein run
```

This will start the server on Port 9099. Run below curl command to verify
```
curl http://localhost:9099/ping
```
Run tests
```
lein test :all
...
Ran 3 tests containing 14 assertions.
0 failures, 0 errors.
```
### Using Java
Build the Jar using below command
```
lein uberjar
```
Initialize the Parking lot Space
```
java -cp target/parking-lot-service-0.1.0-standalone.jar parking_lot_service.slots
```
Run the following command to start the API server
```
java -jar target/parking-lot-service-0.1.0-standalone.jar
```
Use the curl command mentioned above to verify.

### Using Postman to use APIs
Export the <a href="/tools/parking_service.postman_collection.json">Postman Collection</a> to Postman. This collections
includes the sample request for all the APIs.
