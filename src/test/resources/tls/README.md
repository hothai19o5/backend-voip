`selfsigned-wrong-host.p12` is a test-only PKCS12 fixture, password
`test-only-password`. It contains an RSA key and a self-signed certificate with
CN/SAN `fusionpbx-test.invalid`, deliberately mismatching the loopback IP used by
the HTTPS integration tests. It is unrelated to the real FusionPBX server.
Never use this public test key as a server identity outside tests.
