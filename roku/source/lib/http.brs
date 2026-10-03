' Asking servers, from a Task (never the render thread, which Roku won't let block).

' The answer: { code, body, json, headers }, code 0 when nothing came back in time.
function Http_Ask(url as string, method = "GET" as string, headers = invalid as dynamic, body = "" as string, timeoutMs = 20000 as integer) as object
    t = Http_Transfer(url, method, headers)
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    started = false
    if method = "GET" then
        started = t.AsyncGetToString()
    else
        started = t.AsyncPostFromString(body)
    end if
    result = { code: 0, body: "", json: invalid, headers: [] }
    if not started then return result
    msg = Wait(timeoutMs, port)
    if type(msg) = "roUrlEvent" then
        result.code = msg.GetResponseCode()
        result.body = msg.GetString()
        result.headers = msg.GetResponseHeadersArray()
        if result.body <> "" then result.json = ParseJson(result.body)
    else
        t.AsyncCancel()
    end if
    return result
end function

function Http_Transfer(url as string, method as string, headers as dynamic) as object
    t = CreateObject("roUrlTransfer")
    t.SetUrl(url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.RetainBodyOnError(true)
    t.EnableEncodings(true)
    ' Cookies kept by the Roku as well as handed in by hand (Reely's session is one).
    t.EnableCookies()
    if headers <> invalid then t.SetHeaders(headers)
    t.SetRequest(method)
    return t
end function

' Several addresses asked at once: the index of the best that answers (the first in the
' list of those that do), or -1 when none does in time.
function Http_FirstAnswering(urls as object, headers as dynamic, timeoutMs = 6000 as integer) as integer
    port = CreateObject("roMessagePort")
    transfers = []
    ' 0 not yet, 1 answered, 2 refused or failed.
    state = []
    for each u in urls
        t = Http_Transfer(u, "GET", headers)
        t.SetMessagePort(port)
        t.AsyncGetToString()
        transfers.Push(t)
        state.Push(0)
    end for
    clock = CreateObject("roTimespan")
    while true
        ' The best that answered, with nothing better still out: that one.
        pending = false
        for i = 0 to state.Count() - 1
            if state[i] = 1 then return i
            if state[i] = 0 then
                pending = true
                exit for
            end if
        end for
        if not pending then return -1
        remaining = timeoutMs - clock.TotalMilliseconds()
        if remaining <= 0 then exit while
        msg = Wait(remaining, port)
        if type(msg) <> "roUrlEvent" then exit while
        for i = 0 to transfers.Count() - 1
            if transfers[i].GetIdentity() = msg.GetSourceIdentity() then
                code = msg.GetResponseCode()
                if code >= 200 and code < 300 then state[i] = 1 else state[i] = 2
            end if
        end for
    end while
    for i = 0 to state.Count() - 1
        if state[i] = 1 then return i
    end for
    return -1
end function
