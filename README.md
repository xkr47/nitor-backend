Fully async rest and static file serving backend based on vert.x

Goals:
- single runnable über jar
- no need for apache/ngingx/varnish in front
- servers both static files and rest services

Future goals:
- make the stack more configurable so that it can be used in other projects too

Some features of apache/ngingx/varnish that might be useful to reimplement
- access log
- modifying/cleaning up of incoming requests
  - url, headers
- caching of dynamic content
  - cache to disk
  - on-the-fly compression
  - configurable cache key (query parameters, cookies, vary headers etc)
  - cache invalidation api
- authentication
  - TLS client certificate validation
  - basic authentication
    - against ldap
- rate limiting

Ideas
- define request handling attributes in yaml file
  - match rules and action rules
  - match against path/header/query parameter etc
  - actions can be
    - cache (with settings x)
    - cors (with settings x)
  - or is it better to have just a fluent api?
