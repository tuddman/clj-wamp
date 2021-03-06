# clj-wamp

A Clojure implementation of the WebSocket Application Messaging Protocol (v2).

Provides hooks for common WebSocket messaging patterns and error handling (RPC, PubSub, and Authentication).

For information on the **WAMP v2 specification**, visit [wamp.ws](http://wamp.ws).


### Components

the `router` wraps and extends **http-kit** and exposes `Broker` and `Dealer` roles

For information on **HTTP Kit**, a Ring-compatible HTTP & WebSocket server for Clojure, visit [http-kit.org](http://http-kit.org/).

the `client` node exposes `Publisher` and  `Subscriber` and `Caller` and `Callee` roles.


## Status

This library originally supported running a `server` for v1 of the WAMP spec. Version 1 has been largely deprecated. Some work has been done to implement some of the v2 features of a `client`

WIP to bring this library up to support for v2 of the spec for both a `client` (Publisher | Subscriber | Caller | Callee ) and to fold the v1 `server` into a v2 `router` (Broker | Dealer)

**NOT RECOMMENDED** for production systems until a stable release can be tested and published.




## Usage

Add the following dependency to your existing `project.clj` file:
```clojure
[tuddman/clj-wamp "2.1.0"]
```

Run clj-wamp's http-kit-handler within http-kit's with-channel context:

```clojure
(ns clj-wamp-example
  (:require [org.httpkit.server :as http-kit]
            [clj-wamp.router :as wamp]))

; Topic URIs
(defn rpc-url [path] (str "http://clj-wamp-example/api#"   path))
(defn evt-url [path] (str "http://clj-wamp-example/event#" path))

(def origin-re #"https?://myhost")

(defn my-wamp-handler
  "Returns a http-kit websocket handler with wamp subprotocol"
  [request]
  (wamp/with-channel-validation request channel origin-re
    (wamp/http-kit-handler channel
      ; Here be dragons... all are optional
      {:on-open        on-open-fn
       :on-close       on-close-fn

       :on-call        {(rpc-url "add")    +                       ; map topics to RPC fn calls
                        (rpc-url "echo")   identity
                        :on-before         on-before-call-fn       ; broker incoming params or
                                                                   ; return false to deny access                         :on-after-error    on-after-call-error-fn
                        :on-after-success  on-after-call-success-fn }

       :on-subscribe   {(evt-url "chat")     chat-subscribe?  ; allowed to subscribe?
                        (evt-url "prefix*")  true             ; match topics by prefix
                        (evt-url "sub-only") true             ; implicitly allowed
                        (evt-url "pub-only") false            ; subscription is denied
                        :on-after            on-subscribe-fn }

       :on-publish     {(evt-url "chat")     chat-broker-fn   ; custom event broker
                        (evt-url "prefix*")  true             ; pass events through as-is
                        (evt-url "sub-only") false            ; publishing is denied
                        (evt-url "pub-only") true
                        :on-after            on-publish-fn }

       :on-unsubscribe on-unsubscribe-fn

       :on-auth        {:allow-anon? true                ; allow anonymous authentication?
                        :secret      auth-secret-fn      ; retrieve the auth key's secret
                        :permissions auth-permissions-fn ; return the permissions for a key
                        :timeout     20000}})))          ; close the connection if not auth'd


(http-kit/run-server my-wamp-handler {:port 8080})
```

## Documentation

To see [codox](https://github.com/weavejester/codox) generated docs & for more information on the API and callback signatures.

Assuming you have `codox` installed as a plugin in your `~/.lein/profiles.clj` :

```
lein codox
cd target/docs
```

...then browse to index.html in the `target/docs` directory


## Change Log

[CHANGES.md](https://github.com/tuddman/clj-wamp/blob/master/CHANGES.md)

## Contributors

* [Ninerian](https://github.com/Ninerian/clj-wamp/) - version-2
* [DoctorBud](https://github.com/DoctorBud/clj-wamp) - version-2-server
* [sundbry](https://github.com/sundbry/clj-wamp) - version-2
* [cjmartin](https://github.com/cgmartin/clj-wamp)  - Original Author

Pull requests are most welcome!

## License

Copyright © 2013 Christopher Martin

Distributed under the Eclipse Public License, the same as Clojure.
