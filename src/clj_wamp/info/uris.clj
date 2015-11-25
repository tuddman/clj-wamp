(ns clj-wamp.info.uris)

; Predefined URIs
(def ^:const wamp-error-uri-table
  {:invalid-uri "wamp.error.invalid_uri"
   :no-such-procedure "wamp.error.no_such_procedure"
   :procedure-already-exists "wamp.error.procedure_already_exists"
   :no-such-registration "wamp.error.no_such_registration"
   :no-such-subscription "wamp.error.no_such_subscription"
   :invalid-argument "wamp.error.invalid_argument"
   :system-shutdown "wamp.error.system_shutdown"
   :close-realm "wamp.error.close_realm"
   :goodbye-and-out "wamp.error.goodbye_and_out"
   :not-authorized "wamp.error.not_authorized"
   :authorization-failed "wamp.error.authorization_failed"
   :no-such-realm "wamp.error.no_such_realm"
   :no-such-role "wamp.error.no_such_role"
   ; Errors below are not part of the specification
   :internal-error "wamp.error.internal-error"
   :application-error "wamp.error.application_error"
   :bad-request "wamp.error.bad-request"
   :runtime-error "wamp.error.runtime_error"})

(defmacro error-uri
  [error-keyword]
  (get wamp-error-uri-table error-keyword))

