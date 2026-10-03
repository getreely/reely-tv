sub init()
    m.top.functionName = "fetch"
end sub

' request: { url, method?, headers?, body?, timeoutMs?, tag? }
' response: { tag, code, body, json } — code 0 when nothing answered.
sub fetch()
    req = m.top.request
    t = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    t.SetUrl(req.url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.RetainBodyOnError(true)
    t.EnableEncodings(true)
    if req.headers <> invalid then t.SetHeaders(req.headers)
    method = "GET"
    if req.method <> invalid then method = req.method
    t.SetRequest(method)
    timeout = 20000
    if req.timeoutMs <> invalid then timeout = req.timeoutMs
    started = false
    if method = "GET" then
        started = t.AsyncGetToString()
    else
        body = ""
        if req.body <> invalid then body = req.body
        started = t.AsyncPostFromString(body)
    end if
    result = { tag: req.tag, code: 0, body: "", json: invalid }
    if started then
        msg = Wait(timeout, port)
        if type(msg) = "roUrlEvent" then
            result.code = msg.GetResponseCode()
            result.body = msg.GetString()
            if result.body <> "" then result.json = ParseJson(result.body)
        else
            t.AsyncCancel()
        end if
    end if
    m.top.response = result
end sub
