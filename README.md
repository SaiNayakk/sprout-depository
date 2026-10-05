# sprout-depository

The **Sprout Depository**: a simulated securities depository (like NSDL or CDSL), part of "the
outside world" beside Sprout Bank and the exchange. It holds investors' shares electronically in demat
accounts and moves them only on a valid instruction. No real securities exist here.

- **Depository participants** (brokers; Sprout is one) open a demat account per client (16-digit id: the participant's DP id, then the client's number) and read its holdings and movements.
- **The clearing corporation** settles trades: a pay-in takes a seller's shares into its settlement account, a pay-out delivers a buyer's.
- **Every transfer is one transaction**: the instruction id is unique (repeating it returns the original; a different transfer under it is refused), both holdings are locked in a fixed order (no deadlocks), and a client can never go below zero. A pay-in for more than they hold is refused whole.
- **Every movement is kept**, so any holding can be explained line by line.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`depository-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/depository-v1.yaml)
in sprout-contracts. It runs inside the **street** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
