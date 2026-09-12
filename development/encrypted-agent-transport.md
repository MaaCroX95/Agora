# Encrypted agent transport

The API key is the intentional authentication root. Compromise of that key or of
its authorized holder is outside the network-attacker threat model; do not add
extra authentication steps to compensate for that design choice. Windows service
credential files, backups and secret-bearing registry keys allow only Administrators
and SYSTEM. Network attackers are assumed able to observe, replace, delay, repeat,
truncate and redirect HTTP traffic without knowing the key.

Conch and Filo must validate the complete authenticated request before native work.
An unsigned HTTP error cannot prove non-execution or authorize a mutating retry.
Encrypted streams must authenticate event kind and sequence as well as bytes. Missing,
duplicated, reordered, cross-request and post-terminal frames cannot become success.
Neither redirects nor a connection failure may transparently replay a command.
Encrypted file/job responses also require the server response HMAC, using a distinct
HKDF subkey of the request's fresh session key and binding status and ciphertext.
A valid request ciphertext reflected by a network intermediary is not a response.
Update the server before admitting the corresponding strict client; do not silently
downgrade response verification for old installations.

Read limits apply before UTF-8 conversion, JSON parsing or decryption, including
chunked bodies, decompression, error bodies and lines lacking a newline. Conch uses
64 KiB for handshake/errors, 40 MiB for encrypted file/job responses and 10 MiB for
a wire event line, matching its Go client. Limits are independent of Content-Length.
The Conch-specific client retains Agora's proxy configuration and stream cancellation
ownership; these rules must not change ordinary Provider transport behavior.

Qualification requires isolated HTTP fixtures for tampering, replay, timeout, framing,
read bounds and legitimate key rotation. A source fix, a passed focused test and an
installed fleet update are distinct statuses. Do not declare the old mobile parser
safe merely because a server can emit authenticated framing metadata.
