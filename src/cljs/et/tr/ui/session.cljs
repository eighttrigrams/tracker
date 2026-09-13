(ns et.tr.ui.session
  "Decisions about who this browser is signed in as, separated from the effects
  that act on them.

  There is one of them so far, and the separation is the point. It lived inside
  `et.tr.ui.state.auth`, which reaches `ajax.core` through `et.tr.ui.api` and so
  cannot be loaded by the node suite at all — and a branch that cannot be tested
  because of its *neighbours* is a branch that rots. That untestability was a
  fact about where the decision sat and never about the decision: *given the user
  a response is about, and the user this browser is on now, does the answer still
  apply?* needs no network, no promise and no key.")

(defn answer-still-applies?
  "Whether a `GET /api/auth/me` answer about `user` may be merged into
  `current-user` — that is, whether the browser is still on the session it asked
  about.

  **The one it exists to refuse** is a late answer for somebody else. A dev
  switch is two clicks apart and this is one HTTP call, so an answer in flight
  for `antonio` can land after the browser has moved to `daniel`. Merging it
  writes one person's `seal_prose` onto another person's session — which is the
  single mistake the whole seal is arranged to prevent, since a client that
  believes it seals when it does not has its writes refused, and one that
  believes it does not seal when it does puts readable prose in a sealed column.

  Asked on the **username**, not the id, because the id is `nil` for the
  synthetic admin and `nil = nil` would make every admin answer apply to every
  admin-shaped state. An answer carrying no username at all is refused for the
  same reason rather than compared.

  A refusal costs one stale flag until the next refresh, which is the cheap
  direction; the merge is the expensive one and cannot be undone."
  [user current-user]
  (boolean (and (some? user)
                (some? (:username user))
                (= (:username user) (:username current-user)))))
