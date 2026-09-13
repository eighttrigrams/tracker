(ns et.tr.ui.session-test
  "The one decision `et.tr.ui.state.auth` makes that is not an effect.

  It had no test until a neighbouring agent pointed out that *the branch is a
  pure predicate wearing an ajax namespace's clothes* — the untestability was a
  property of where it lived, not of what it decided. So it moved, and this is
  what it is worth."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [et.tr.ui.session :as session]))

(deftest an-answer-about-the-user-we-are-still-on-applies
  (is (session/answer-still-applies? {:username "antonio" :seal_prose true}
                                     {:username "antonio"}))
  (testing "and the synthetic admin is a session like any other"
    (is (session/answer-still-applies? {:username "admin" :id nil :seal_prose false}
                                       {:username "admin" :id nil}))))

(deftest a-late-answer-for-somebody-else-is-dropped
  ;; The race this exists for: switch away while a `/api/auth/me` is in flight.
  ;; Merging it writes one person's sealing flag onto another person's session.
  (is (not (session/answer-still-applies? {:username "antonio" :seal_prose true}
                                          {:username "daniel"})))
  (testing "including to and from the admin, whose id is nil and would compare equal
    to any other nil — which is why this asks the username and not the id"
    (is (not (session/answer-still-applies? {:username "admin" :id nil}
                                            {:username "antonio" :id 2})))
    (is (not (session/answer-still-applies? {:username "antonio" :id 2}
                                            {:username "admin" :id nil})))))

(deftest an-answer-with-nothing-to-compare-is-dropped-rather-than-guessed-at
  (is (not (session/answer-still-applies? nil {:username "antonio"})))
  (is (not (session/answer-still-applies? {:seal_prose true} {:username "antonio"}))
      "an answer carrying no username is refused, not compared")
  (is (not (session/answer-still-applies? {:username "antonio"} nil))
      "and so is an answer arriving before there is a session to apply it to")
  (is (not (session/answer-still-applies? {:username nil} {:username nil}))
      "two absent names are not the same name")
  (is (false? (session/answer-still-applies? nil nil))
      "and the answer is always a boolean, since a `when` reads it"))
