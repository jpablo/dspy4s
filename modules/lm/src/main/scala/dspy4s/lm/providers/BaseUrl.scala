package dspy4s.lm.providers

/** The rules of the base URL of a provider. */
private[providers] object BaseUrl:

  /** `baseUrl` with no slash at its end: each slash at the end is removed, not only one (upstream
    * `base_url.rstrip("/")`). The URL of an endpoint is this root, then the path of the endpoint.
    */
  def root(baseUrl: String): String =
    baseUrl.reverse.dropWhile(_ == '/').reverse
