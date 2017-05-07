Fully async rest and static file serving backend based on vert.x

## Installation

### Building
```sh
mvn clean package
```

### Running at port 443
```sh
mkdir -p /opt/nitor/backend
cp -a target/backend-1.0.0-fat.jar /opt/nitor/backend
cp -a certs /opt/nitor/backend
cp src/systemd/* /etc/systemd/system
systemd daemon-reload
systemd start nitor-backend.socket
```

## Health check

The url `/healthCheck` will always be available unauthenticated and without client certificates.

## Configuration

Configuration is done by config.json file in the current working directory. Most options can be also overriden with system property.

The only exception is the listen port that is specified either by system property `port` (that has default value of 8443) or if by the file handle passed in by the systemd/xineted socket listener.
Using the systemd socket listener (as is done in the prepackaged rpm/deb packages allows running the service with limited permissions).

### Server options
```json
  "idleTimeout": 3600,
  "http2": true
```

### Enabling TLS
```json
  "tls": {
    "serverKey": "certs/localhost.key.clear",
    "serverCert": "certs/localhost.crt"
  }
```
The serverCert should include the whole concatenated certificate chain.

### Requiring client certificates
```json
  "clientAuth": {
    "path": "/*",
    "clientChain": "certs/client.chain"
  }
```
The `path` specifies which urls require the certificate.
The url `/certCheck` will always be available and will respond back with plain text information about the cliente certificate that server received.

### Requiring basic authentication
```json
  "basicAuth": {
    "path": "/*",
    "realm": "nitor",
    "users": {
      "test": "test",
      "admin": "nimda"
    }
  }
```
The `path` specifies which urls require the basic auth.

### Serving static files
A list of static file locations can be provided.
```json
  "static": [{
    "path": "/static/*",
    "dir": "webroot",
    "readOnly": false,
    "cacheTimeout": 1800
  }]
```
The `path` specifies where the static files are exposed.
The `dir` specifies where the static files are loaded from.
Setting `readOnly` to false stops caching that assumes that files do not change during the lifetime of the service.

### Serving files from S3
A list of s3 proxies can be provided.
```json
  "s3": [{
    "path": "/s3/*",
    "bucket": "webroot",
    "basePath": "pictures/big",
    "region": "eu-central-1",
    "basePath": "pictures/big",
    "accessKey": "xyz",
    "secretKey": "123"
  }]
```
The `path` specifies where the static files are exposed.
The `bucket` specifies where the S3 bucket.
The optional `basePath` specifies where the path inside the bucket (cannot be escaped).
The optional `region` specifies the S3 region.
The optional `accessKey` specifies the S3 access.
The optional `secretKey` specifies the S3 access.

If the `region` or `accessKey`/`secretKey` -pair is not given then standard AWS sdk code is used to detect/fetch the values from environment or from the AWS instance profile.

### Proxying to another HTTP service
A list of static file locations can be provided.
```json
  "proxy": [{
    "route": "/proxy/*",
    "host": "example.org",
    "port": 80,
    "path": "/",
    "hostHeader": null,
    "receiveTimeout": 300
  }]
```
The `route` specifies where the proxy files are exposed. (Note: different from all other configuration sections).
The `hostHeader` allows setting a `Host` header into the outgoing http request - the original request information is available in `X-Host`, `X-Forwarded-For` and `X-Forwarded-Proto` headers.
The `receiveTimeout` does not seem to work yet correctly.

### Customizing outgoing proxy request or outoing response

A list of customization scripts can be provided.
```json
  "customize": [{
    "path": "/*",
    "jsFile": "custom.js"
  }]
```

The `path` specifies which requests are processed by the customization script.
The `jsFile` specifies which javascript file that customize the operation. The script can mostly only customize the request and response headers, not the body.

### Example Script
```js
var api = {};

api.handleRequest = function(request, context) {
   console.log('REQ:', request.headers());
}

api.handleResponse = function(response, context) {
    console.log('RES:', response.headers());
}

console.log('js customizations loaded');

module.exports = api;
```

### Azure AD authentication and user account information forwarding
A list of static file locations can be provided.
```json
  "adAuth": {
    "path": "/*",
    "clientId": "",
    "clientSecret": "",
    "configurationURI": "https://login.microsoftonline.com/organizations/v2.0/.well-known/openid-configuration",
    "scope": "openid profile https://graph.microsoft.com/user.read",
    "customParam": {
      "domain_hint": "organizations"
    },
    "graphQueryURI": "https://graph.microsoft.com/beta/me?$expand=memberOf",
    "headerMappings": {
      "x-auth-name": ".displayName",
      "x-auth-mail": ".mail",
      "x-auth-phone": ".mobilePhone",
      "x-auth-groups": ".memberOf[].mailNickname"
    },
    "requiredHeaders": {
      "x-auth-groups": "(^|.*,)admin(,.*|$)"
    }
  },
  "session": {
    "serverName": "my-service.domain.com",
    "secretFile": ".secret",
    "sessionAge": 1209600
  }
```
The `path` specifies which urls require the Azure AD auth. The microsoft side of the application is configured in [https://apps.dev.microsoft.com](apps.dev.microsoft.com).
The `graphQueryURI` specifies the extra rest query done to microsoft graph v2 api to fetch account details.
The `headerMappings` maps the json data to headers.
The `requiredHeaders` runs regexp validaton on the headers to decide if the account has access.
The `session` section configures the stateless cookie that is generated to the client. Do not that if there is a cluster of servers then the `serverName` and the contents of the `secretFile` contents must match on each node.


## Goals

- single runnable über jar
- no need for apache/ngingx/varnish in front
- servers both static files and rest services

## Future goals
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
  - TLS client certificate validation (done)
  - basic authentication
    - against ldap
- rate limiting

## Ideas
- define request handling attributes in yaml file
  - match rules and action rules
  - match against path/header/query parameter etc
  - actions can be
    - cache (with settings x)
    - cors (with settings x)
  - or is it better to have just a fluent api?
