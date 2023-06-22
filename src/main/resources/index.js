var connection;
var debugMode = false;
var reconnectTimeout;
var hostname = location.hostname;
var protocol = location.protocol;
var port = location.port;
var wsProtocol = 'ws';
var httpUrl = '';

function getParameterByName(name, url = window.location.href) 
{
    name = name.replace(/[\[\]]/g, '\\$&');
    var regex = new RegExp('[?&]' + name + '(=([^&#]*)|&|#|$)'),
        results = regex.exec(url);
    if (!results) return null;
    if (!results[2]) return '';
    return decodeURIComponent(results[2].replace(/\+/g, ' '));
}

function padString(str, len) {
  if (str.length < len) {
    return str.padEnd(len, ' ');
  } else if (str.length > len) {
    return str.slice(0, len);
  } else {
    return str;
  }
}

function updateKCS(v)
{
    if (v)
    {
        document.getElementById('connectionLed').src="led-green.svg";
        logIt("KISS Server Connected");
    } else {
        document.getElementById('connectionLed').src="led-red.svg";
        logIt("KISS Server Disconnected");
    }
}

function doTxWindow()
{
    var myWindow = window.open('tx.html?apiPassword=' + encodeURIComponent(document.getElementById('password').value), "Transmit", "width=455,height=335");
}

function sendEvent(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    if (debugMode)
        console.log("Transmit: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}

function doAuth()
{
    sendEvent({
        "apiPassword": document.getElementById('password').value
    });
}


function setupWebsocket()
{
    try
    {
        if (hostname == '')
        {
            debugMode = true;
            hostname = '127.0.0.1';
            protocol = 'http';
            port = 8101;
            httpUrl = "http://127.0.0.1:8101/";
        }
        if (protocol.startsWith('https'))
        {
            wsProtocol = 'wss';
        }
        connection = new WebSocket(wsProtocol + '://' + hostname + ':' + port + '/jaxt/');
        
        connection.onopen = function () {
            if (document.getElementById('login').style.display == 'none')
            {
                doAuth();
            }
        };
        
        connection.onerror = function (error) {
            //document.getElementById('connectionLed').src="led-grey.svg";
        };

        //Code for handling incoming Websocket messages from the server
        connection.onmessage = function (e) {
            if (debugMode)
            {
                console.log("Receive: " + e.data);
            }
            var jsonObject = JSON.parse(e.data);
            if (jsonObject.hasOwnProperty("action"))
            {
                var action = jsonObject.action;
                if (action == 'authOk') {
                    document.getElementById('login').style.display = 'none';
                    document.getElementById('console').style.display = 'block';
                    if (!jsonObject.txDisabled)
                    {
                        document.getElementById('txButton').style.display = 'inline-block';
                    }
                    updateKCS(jsonObject.kissConnected)
                    sendEvent({
                        "history": 100
                    });
                } else if (action == 'authFail') {
                    document.getElementById('errorMsg').innerHTML = jsonObject.error;
                } else if (action == 'kissConnected') {
                    updateKCS(true);
                } else if (action == 'kissDisconnected') {
                    updateKCS(false);
                }
            } else if (jsonObject.hasOwnProperty("source") && jsonObject.hasOwnProperty("destination") && jsonObject.hasOwnProperty("control")) {
                logPacket(jsonObject);
            }
        };
        
        connection.onclose = function () 
        {
            document.getElementById('connectionLed').src="led-grey.svg";
            console.log('WebSocket connection closed');
            reconnectTimeout = setTimeout(setupWebsocket, 10000);
        };
    } catch (err) {
        console.log(err);
    }
}

function getDTString(date)
{
    var now = new Date();
    var nowDateString = now.toLocaleDateString();
    var dateString = date.toLocaleDateString();
    var timeString = date.toLocaleTimeString();
    if (nowDateString != dateString)
    {
        return dateString + ' ' + timeString;
    } else {
        return timeString;
    }
}

function logIt(message)
{
    var console = document.getElementById('console');
    var d = new Date();
    var msgSplit = message.split(/\r?\n/);
    for (var i = 0; i < msgSplit.length; i++)
    {
        console.innerHTML +=  "(INFO " + getDTString(d) + ") " + msgSplit[i] + "\n";
    }
    window.scrollTo(0,document.body.scrollHeight);
}

function cleanPayload(payload)
{
    if (payload != undefined)
        return payload.replaceAll(/</g,'&lt;').replaceAll(/>/g,'&gt;').replaceAll(/\r/g,'&lt;CR&gt;').replaceAll(/\n/g,'&lt;LF&gt;');
    else
        return "";
}

function logPacket(packet)
{
    var console = document.getElementById('console');
    var d = new Date();
    if (packet.hasOwnProperty('timestamp'))
        d = new Date(packet.timestamp);
    var ctrlStr = "";
    for(flag of packet.control)
    {
        ctrlStr += " " + flag;
    }
    console.innerHTML +=  "(" + packet.direction.toUpperCase() + " @ " + getDTString(d) + ") " + padString(packet.source,9) + " > " + padString(packet.destination,9) + " [" + padString(ctrlStr,14) + " ] " + cleanPayload(packet.payload) + "\n";
    window.scrollTo(0,document.body.scrollHeight);
}

window.onload = function() {
    setupWebsocket();
};

