# Stateless cookie crypto

## cookie content format
`key=value|key2=value2|...`

## cookie fixed contents

These fields are stored in the beginning of the cookie in this order
* `r=[1-5 digit random hex]` - just here to enhance the compression engines output randomness - separate random of vale (0-FFFFF) and the length (1-5)
* `s=[higher 16 bits of IP address string hashcode in hex]` - optional: we limit the cookie to a specific source ip
* `t=[expiration time since epoc in seconds as hex]` - should match the expiration sent with the cookie to the browser
* `u=[user agent string hashcode in hex]` - to limit the cookie usage to specific user agent
* `h=[public hostname/domain of server string hashcode in hex]` - limit the cookie to this specific host/domain (should match the host/domain of the cookie)

Alternative: we could combine the hash of the s+u+h fields into one - it would save space but would 

After that the custom application state is stored in any order.

## binding to source ip

If configured the cookie can be limited to specific source ip address. The cookie lifetime can be increased more securitly, but all new locations need a new authentication.
In contrast to traditional session cookies the cookie name will also differ for each source IP so that there will be parallel sessions for each location.

## cookie name

If the cookie is bound to source ip then the cookie name needs to be vary based on the source ip address.

`auth[lower 16 bits of IP address string hashcode in hex]`. The use of IP address in the name allows having parallel sessions from multiple IP addresses, which is useful when user keeps jumping between few IP addresses. The fixed cookie name would overwrite the previous session in the browser.

Question: Should server send remove-cookie for oldest cookies to the client if there are too many (10+) cookies in the request?

## crypto

Encryption will be done with AESGCM which is an AEAD algorithm that provides both verification and encyrption at the same time.
The speed (or slowness) of the chosen algorithm will not matter since the cookies will be relatively long lived and the server will cache the seen valid cookies in memory.

## compression

Optional deflate compression of the content will be used. While compressing before encrypting can reduce the encryption security the randomness to the compression engine from the semi-random data in the beginning should offset it.
Deflate is better than gzip because deflate omits the header and checksum. Especially the fixed header could make the encryption easier to crack.

## final cookie encoding

`base64(AESGCM(deflate(utf8bytes("key=value|key2=value|..."))))`

## security evaluation

The binding of cookies to user agent and source ip address limits the harm done by leaked cookies by almost completely removing the offline leaked cookie reuse.
The source ip address helps when a stolen (or US border customs captured) device is enabled in different location.
The random length random number in the beginning will help create more random output of even bad ciphers.

## leaked cookies

If we suspect some tokens have been compromised we can change the crypto secret of all nodes and thus invalidate all existing tokens.
