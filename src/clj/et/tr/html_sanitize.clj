(ns et.tr.html-sanitize
  "Server-side HTML sanitization for content imported from external
  sources (atom/RSS feeds, web clippers, etc.) before it is stored as a
  :type \"html\" message. Strips scripts, styles, and event handlers;
  keeps a relaxed set of text/structural tags plus images and links.
  Links are forced to target=_blank rel=noopener."
  (:import [org.jsoup Jsoup]
           [org.jsoup.safety Safelist]))

(defn- safelist ^Safelist []
  (-> (Safelist/relaxed)
      (.addAttributes "a" (into-array String ["target" "rel"]))
      (.addEnforcedAttribute "a" "rel" "noopener")
      (.addEnforcedAttribute "a" "target" "_blank")))

(defn sanitize
  "Returns a sanitized HTML string. nil-safe."
  [html]
  (when html
    (Jsoup/clean (str html) (safelist))))
