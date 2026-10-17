# Order fulfillment API v1

Staff can fulfill an order in multiple shipments through the authenticated Backoffice API.

## Create a shipment

`POST /api/v1/admin/orders/{orderId}/shipments` requires ADMIN or SUPERADMIN. A manual shipment includes a tracking number; a carrier-created shipment includes a selected namespaced `shippingMethodId` and an `Idempotency-Key`. Both requests contain between 1 and 100 unique order lines:

```json
{
  "trackingNumber": "CH-TRACK-123",
  "shipmentReference": "carrier-shipment-123",
  "items": [
    { "orderItemId": "00000000-0000-0000-0000-000000000001", "quantity": 2 }
  ]
}
```

To create a distinct carrier label for a parcel, omit `trackingNumber` and provide the selected method returned by shipping discovery. The method identity is namespaced by provider instance (for example `{instance-uuid}:priority`), and an optional parcel weight overrides the order's checkout weight:

```http
Idempotency-Key: parcel-01-order-123
```

```json
{
  "shippingMethodId": "00000000-0000-0000-0000-000000000001:priority",
  "parcelWeightKg": 1.25,
  "items": [
    { "orderItemId": "00000000-0000-0000-0000-000000000001", "quantity": 1 }
  ]
}
```

Core revalidates that the exact selected carrier method is still available, calls only its provider instance, and persists the returned tracking/reference, provider ownership and label with the shipment. The same idempotency key and request returns the saved shipment; reusing it for a different request returns `409`. An ambiguous provider result can be retried with that same key, which reuses the durable Core operation and provider idempotency identity. `GET /api/v1/admin/orders/{orderId}/shipments/{shipmentId}/label` serves a validated PDF label without caching it.

Only `PAID`, `CONFIRMED`, and `PARTIALLY_SHIPPED` orders may be fulfilled. Line quantities use decimal values with at most four decimal places, matching the inventory quantity model. The API locks the order while checking each line's unfulfilled quantity, so concurrent requests cannot ship more than was ordered. A tracking number is unique within an order. A repeated tracking number returns `409 Conflict`.

Each accepted shipment and its order-line quantities are persisted transactionally. Shipment creation allocates the corresponding reserved stock and reduces on-hand quantity by exactly the quantity shipped, including bundle component order lines. Any remaining reservation is allocated once every order line has shipped. The order status is `PARTIALLY_SHIPPED` until all line quantities are fulfilled, then `SHIPPED`.

`GET /api/v1/admin/orders/{orderId}/shipments` lists shipments in creation order, including each parcel's label URL and carrier reference. Order detail line records expose `shippedQuantity` alongside ordered `quantity`. `GET /api/v1/admin/orders` accepts `PARTIALLY_SHIPPED` as a status filter.

Carrier tracking is refreshed per parcel with `POST /api/v1/admin/orders/{orderId}/shipments/{shipmentId}/tracking/refresh`. Each request uses that shipment's saved provider instance and account snapshot. A multi-parcel order becomes `DELIVERED` only after every shipment with a tracking reference has a latest `DELIVERED` event.

Shipment creation records an order timeline event and publishes the usual private order update. Manual tracking remains supported when no label is requested. Each carrier label operation carries only the exact parcel's line quantities and a stable per-shipment idempotency identity; Core never broadcasts label creation to multiple eligible carriers.

## Reservation semantics

An inventory reservation retains its original quantity and tracks allocated and released quantities separately. Remaining reserved quantity is `quantity - allocatedQuantity - releasedQuantity`. Allocating or releasing a subset is supported; the state remains `RESERVED` while a remainder exists. Once no quantity remains, the state is `ALLOCATED` if any quantity shipped and `RELEASED` otherwise. Existing full-allocation and full-release operations continue to consume or release the remaining balance.
