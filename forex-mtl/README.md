### Problem Formulation
- The application needs to support much higher request volume than what the source (OneFrame API) is capable of.
- Result returned doesn't need to be current and can be older within certain limit.

In conclusion the solution also needs to support caching. Because the cache  can be modified concurrently by multiple requests, it needs to deal with race condition.

### Implementations
- Reading from local cache, updating it from OneFrame, and returning the result should be done in one atomic operation. Mutex/lock will be used for this purpose.
- The test needs to show how many times the client is fired during a period. This is to ensure that OneFrame's rate limit is respected.
- Bad parameters (poorly-formatted or non-supported currency pairs) should be intercepted on request layer. They will not be passed to OneFrame.
- OneFrame supports multiple pairs in a single request. Because it seems there is no extra cost for doing it, we should simply update all rates at each request.

### To run
Open `application.conf`and set the environment for `live`.
- url : OneFrame endpoint.
- refresh: Cache age in second. This should be set between 300 (5 min) to 90 (around OneFrame's rate limit).

Run `Main`.