# Order fulfillment API v1

Staff can fulfill an order in multiple shipments through the authenticated Backoffice API.

## Create a shipment

`POST /api/v1/admin/orders/{orderId}/shipments` requires ADMIN or SUPERADMIN. The request must include one tracking number and between 1 and 100 unique order lines:

```json
{
  "trackingNumber": "CH-TRACK-123",
  "shipmentReference": "carrier-shipment-123",
  "items": [
    { "orderItemId": "00000000-0000-0000-0000-000000000001", "quantity": 2 }
  ]
}
```

Only `PAID`, `CONFIRMED`, and `PARTIALLY_SHIPPED` orders may be fulfilled. Line quantities use decimal values with at most four decimal places, matching the inventory quantity model. The API locks the order while checking each line's unfulfilled quantity, so concurrent requests cannot ship more than was ordered. A tracking number is unique within an order. A repeated tracking number returns `409 Conflict`.

Each accepted shipment and its order-line quantities are persisted transactionally. Shipment creation allocates the corresponding reserved stock and reduces on-hand quantity by exactly the quantity shipped, including bundle component order lines. Any remaining reservation is allocated once every order line has shipped. The order status is `PARTIALLY_SHIPPED` until all line quantities are fulfilled, then `SHIPPED`.

`GET /api/v1/admin/orders/{orderId}/shipments` lists shipments in creation order. Order detail line records expose `shippedQuantity` alongside ordered `quantity`. `GET /api/v1/admin/orders` accepts `PARTIALLY_SHIPPED` as a status filter.

Shipment creation records an order timeline event and publishes the usual private order update. This manual fulfillment endpoint records carrier references provided by staff; carrier label creation and carrier-side partial parcel contents require a provider contract that accepts per-shipment line quantities.

## Reservation semantics

An inventory reservation retains its original quantity and tracks allocated and released quantities separately. Remaining reserved quantity is `quantity - allocatedQuantity - releasedQuantity`. Allocating or releasing a subset is supported; the state remains `RESERVED` while a remainder exists. Once no quantity remains, the state is `ALLOCATED` if any quantity shipped and `RELEASED` otherwise. Existing full-allocation and full-release operations continue to consume or release the remaining balance.
