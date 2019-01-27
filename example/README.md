# io.datopia/abci-example

Currently contains a [single, literate example](src/abci/example/kv.clj), analogous to
Tendermint's
[kvstore.go](https://github.com/tendermint/tendermint/blob/master/abci/example/kvstore/kvstore.go).

## Running

```
$ lein run -m abci.example.kv
```

The above invocation'll bind to `io.datopia/abci`'s default port: `26658`.
Assuming you've pointed a Tendermint process at it, you can issue transactions
with `bin/insert-map`, which communicates with Tendermint's HTTP RPC API over
port `26657`.  If you're running Tendermint via Docker, you'll want something like
`-p 26657:26657` in your `docker run` invocation.

```
$ bin/insert-map '{:a 1 :b 2}'
```
