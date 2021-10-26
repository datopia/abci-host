# org.datopia/abci-example

Currently contains a [single, literate example](src/abci/example/kv.clj), analogous to
Tendermint's
[kvstore.go](https://github.com/tendermint/tendermint/blob/master/abci/example/kvstore/kvstore.go).

## Running

```
$ lein kv
```

The above invocation'll bind to `org.datopia/abci`'s default port: `26658`.
Assuming you've pointed a Tendermint process at same (`--proxy_app=tcp://...`),
you can issue transactions with `bin/insert-map`, which communicates with
Tendermint's HTTP API over port `26657` (unless `--port=N` is specified):

```sh
$ echo '{:a 1}'     | bin/insert-map
$ echo '{:b ["c"]}' | bin/insert-map --port=26000'
```

## Local Cluster

When developing, it's most convenient to point a single `tendermint` process at
an `org.datopia/abci` application running in a REPL --- though it's relatively
simple to setup a local cluster, if that's your thing.
With [docker-compose](https://docs.docker.com/compose/), and our
tragic [compose file](docker-compose.yml), you'll be minting blocks in no time.

As-is, the compose config sets up a 3 node cluster, with containerized
Tendermint instances connecting to JVMs running on the host.  Using Tendermint
via Docker confers practical benefits (ease of testing across versions, etc.),
though these don't extend to the Clojure example application/s, hence the
container &#8594; host architecture.

```sh
example$ rm -rf /tmp/testnet && bin/setup
example$ docker-compose up
example$ ABCI_PORT=26658 lein kv &
example$ ABCI_PORT=26659 lein kv &
example$ ABCI_PORT=26660 lein kv &
example$ echo '{:a [1]}' | bin/insert-map --port=26671
```
