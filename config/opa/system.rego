package system.authz
# Deny access by default.
default allow := false
# Allow checking policies via compile endpoint
allow {
    input.method == "POST"
    input.path == ["v1", "compile"]
}
# Allow health checks
allow {
    input.method == "GET"
    input.path == [""]
}
allow {
    input.method == "GET"
    input.path == ["health"]
}
# Allow prometheus metric collection
allow {
    input.method == "GET"
    input.path == ["metrics"]
}
