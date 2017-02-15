# Stateless cookie crypto

## cookie content format
`key=value|key2=value2|...`

## cookie fixed contents

These fields are stored in the beginning of the cookie in this order
* `r=[1-5 digit random hex]` - just here to enhance the compression engines output randomness
* `s=[higher 16 bits of IP address string hashcode in hex]` - we limit the cookie to a specific source ip
* `t=[expiration time since epoc in seconds as hex]` - should match the expiration set to cookie on the browser level
* `u=[user agent string hashcode in hex]` - to limit the cookie usage to specific user agent
* `h=[public hostname/domain of server string hashcode in hex]` - limit the cookie to this specific host/domain (should match the host/domain of the cookie)

After that the custom application state is stored in any order.

## cookie name

`auth[lower 16 bits of IP address string hashcode in hex]`. The use of IP address in the name allows having parallel sessions from multiple IP addresses, which is useful when user keeps jumping between few IP addresses. The old tokens are overwritten in the browser.

Should server should send remove-cookie for expired cookies to the client if there are more than 10 cookies?

## crypto

Encryption will be done with AESGCM which is an AEAD algorithm that provides both verification and encyrption at the same time.

## compression

Gzip compression of the content will be used. While compressing before encrypting can reduce the encryption security the randomness to the compression engine from the semi-random data in the beginning should offset it.

## final cookie encoding

`base64(AESGCM(gzip(utf8bytes("key=value|key2=value|..."))))`

## leaked cookies

The binding of cookies to user agent and source ip address limits the harm done by leaked cookies.

The source ip address helps when a stolen (or US border customs captured) device is enabled in different location.
 
If we suspect some tokens have been compromised we can change the crypto secret of all nodes and thus invalidate all existing tokens.
