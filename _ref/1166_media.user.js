// ==UserScript==
// @name         媒体资源嗅探及下载(支持下载m3u8和mp4视频和音频)
// @namespace    http://tampermonkey.net/
// @version      1.986
// @description  功能包含：1、自动嗅探页面上的视频、音频资源，列出链接，并提供播放、复制和下载功能（提供 mp3、mp4 和 m3u8 资源下载）；2、录屏；3、解除页面复制限制。
// @author       geigei717
// @license      Copyright geigei717
// @antifeature  ads
// @match        https://*/*
// @match        http://*/*
// @icon         https://greasyfork.s3.us-east-2.amazonaws.com/fc67t00gsk98w7pbhs97xr94g1hl
// @require      https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js
// @require      https://cdnjs.cloudflare.com/ajax/libs/dplayer/1.27.1/DPlayer.min.js
// @require      https://cdnjs.cloudflare.com/ajax/libs/layui/2.9.14/layui.js
// @require      https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.13/hls.js
// @require      https://cdnjs.cloudflare.com/ajax/libs/flv.js/1.6.2/flv.js
// @require      https://update.greasyfork.org/scripts/433051/Trusted-Types%20Helper.user.js

// @require      https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.2.0/crypto-js.min.js

// @resource     LayuiCss  https://cdnjs.cloudflare.com/ajax/libs/layui/2.9.14/css/layui.css
// @resource     qbMediaRecorderJS https://quickblox.github.io/javascript-media-recorder/qbMediaRecorder.js
// @grant        unsafeWindow
// @grant        GM_download
// @grant        GM_setClipboard
// @grant        GM_xmlhttpRequest
// @grant        GM_registerMenuCommand
// @grant        GM_unregisterMenuCommand
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_addStyle
// @grant        GM_addElement
// @grant        GM_getResourceText
// @grant        GM_webRequest
// @connect      *
// @supportURL  【Greasy Fork 脚本技术交流】：http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=IkMlupLSzK9E2MheU0ngdDHHnnzojNYx&authKey=v1p%2BI3vfp3Bw60DIGgWxTtQSQ0NAz4ib%2FC6lTF0LjIi8dteVCtihitq5zID%2FoM0N&noverify=0&group_code=674604829
// ==/UserScript==

(function() {
    "use strict";
    // # 功能设置： 0 关闭，1 开启； 注：此处设置值如果改为（0 or 1）以外的值，会导致相应功能关闭（油猴脚本菜单中的选项也不会起效）
    // 此处的设置主要考虑到手机端使用时没有油猴脚本菜单可以 开启/关闭 功能，故添加于此。
    var set = [];
    set['auto_n']   = 0    // 默认不会在嗅探出资源后自动打开列表
    set['ffmpeg_n'] = 0    // 默认不会在浏览器下载完m3u8视频后进行视频转码 （解码需要浏览器开启特定功能并且会占用大量资源）
    set['ad_n']     = 1    // 默认会在下载m3u8视频时自动过滤其中夹杂的广告，但注意 有些广告使用了混淆 会导致视频片段会被误当做广告而去除，下载的视频不完整，这种情况关闭此功能即可。
    set['checked_n']= 1    // 默认解除特殊网站对文本选中的限制，对开启此功能但仍无法解除选择限制的网站 可以提交网站给作者进行相应优化。 解除限制后可以进行复制等操作（1.70新加功能）。

var xcNum = 15
if (window.self == window.top) {
GM_addElement('script', {
textContent:  GM_getResourceText('qbMediaRecorderJS') });
GM_addStyle( GM_getResourceText("LayuiCss").toString()
.replace(/([^.@-]+{[^}]*}\s*)*/im , '')
.replaceAll(/@font-face\s*\{\s*font-family:\s*layui-icon;[^}]*}/img,
"@font-face { "+
"font-family: layui-icon; "+
"src: url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.eot); "+
"src: url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.eot) format('embedded-opentype'),"+
"     url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.woff2) format('woff2'),"+
"     url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.woff) format('woff'),"+
"     url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.ttf) format('truetype'),"+
"     url(https://cdn.bootcdn.net/ajax/libs/layui/2.8.17/font/iconfont.svg) format('svg')}") );
unsafeWindow.GM = GM;
try {
unsafeWindow.$('body')
} catch(err) {
unsafeWindow.$ = $;
}
} else {
}
var userAgent = navigator.userAgent.toLowerCase();
var platform,weight,weight1,offset;
if(userAgent == null || userAgent == ''){
platform = 'pc' ;
}else{
if(userAgent.indexOf("android") != -1 ){
platform = 'phone';
}else if(userAgent.indexOf("ios") != -1 || userAgent.indexOf("iphone") != -1 || userAgent.indexOf("ipad") != -1){
platform = 'phone';
}else if(userAgent.indexOf("windows phone") != -1 ){
platform = 'phone';
}else{
platform = 'pc' ;
}
}
if(platform == 'pc'){
var menuList = []
var info = ['❌ 已禁用','✅ 已启用']
mkMenu([
{ No : 0 , key: "支持作者的创作  ", KeyName : "me()"},
{ No : 1 , key: "新资源自动打开：", keyName : "auto_n"},
{ No : 2 , key: "是否将视频转码：", keyName : "ffmpeg_n"},
{ No : 3 , key: "是否下载去广告：", keyName : "ad_n"},
{ No : 4 , key: "除文本选中限制：", keyName : "checked_n"},
])
}
var w = window.innerWidth;
var h = window.innerHeight;
if(platform == 'pc'&& w > 800 && h >600){
weight = ['800px', '600px'];
weight1 = ['550px', '700px'];
offset = (h-600)/2+"px"
}else{
if(w < 490){
var z = w/490;
GM_addStyle( '#MyUpDown{zoom: '+z+';-moz-transform: scale('+z+');-moz-transform-origin:right top;;} '+
'#MyUrls{zoom: '+z+';-moz-transform: scale('+z+');-moz-transform-origin:right top;;} '+
'#Allurl{zoom: '+z+';-moz-transform: scale('+z+');-moz-transform-origin: right top;;}; ')
}
if(platform == 'pc'){
offset = w < h ? (h-w)/2+"px" : 0.1*h+"px";
weight = w < h ? [0.8*w+"px", 0.8*w+"px"] : [0.8*h+"px", 0.8*h+"px"];
weight1 = w < h ? [0.8*w+"px",0.8*w+94+"px"] : [0.8*h+"px",0.8*h+94+"px"];
}else{
offset = w < h ? (h-w)/2+"px" : 0.01*h+"px";
weight = w < h ? [0.98*w+"px", 0.98*w+"px"] : [0.98*h+"px", 0.98*h+"px"];
weight1 = w < h ? [0.98*w+"px",0.98*w+94+"px"] : [0.98*h+"px",0.98*h+94+"px"];
}
}
let URLAs = [],videos=[],play = 0,hzh = false;;
var firstVideo = 0, mn = -1;
unsafeWindow.GM_D = [];
var href = location.href;
var origin = location.origin
$("body").attr("id","Top")
.append(["<div id='MyUrls' style='    text-align: left;font-family: \"Times New Roman\",Georgia,Serif !important;;width: 490px;background-color: #ffffff;color: black;position: fixed;top: 1px;right: 1px;z-index: 999999999999999;border-radius: 4px;display: none;'>" +
"   <div id='Allurl' style=''>" +
"      <span id='LupinStart'  title='录屏'>🔴</span>" +
"      <span id='LupinStop'   title='停止录屏' style='display:none;'>⏹️</span>" +
"      <span id='Alldownload' title='下载全部资源'>⬇️</span>" +
"      <span id='Allcopy'     title='复制全部链接'>📋</span>" +
"      <span id='Alldel'      title='清除列表'>🗑️</span>" +
"   </div>"+
"   <hr style='border-color: black;margin: 5px;height: 2px;background: black;border-width: 0;'>" +
"   <div class='MyUrls' style='background-color: #ffffff;border-radius: 4px;margin: 10px 10px;max-height: 500px;text-align: left;'>" +
"      <div id='tab-container'>"+
"         <div id='MyVideo' class='my-tab'>🎬视频</div>"+
"         <div id='MyAudio' class='my-tab'>🎧音频</div>"+
"      </div> "+
"      <div class='MyNR'>"+
"         <div class='MyVideo'></div>  <div class='MyAudio'></div>"+
"      </div> "+
"   </div>" +
"</div>"][0])
.append(["<div id='MyUpDown' style='color: black;position: fixed;top: 1px;right: 1px;z-index: 1000009999999999999;font-size: 20px;line-height: 30px;text-align: center;cursor: pointer;'>" +
"   <div id='redPoint' style='width: 8px; height: 8px; background-color: red; border-radius: 50%; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); display:none;'></div>"+
"   <div id='downIcon' style='width: 30px!important;height: 30px!important;line-height: 30px!important;font-size: 16px !important;font-family: Helvetica!important;'>⤵️</div>"+
"</div>"][0])
.append()
.append(["<a href='#Top' id='GoTop' target='_self'  style='text-decoration: none;display: none; width: 30px;height: 30px;color: black;background-color:rgb(149, 228, 246);position: fixed; bottom: 50px;right: 1px;z-index: 9999999100000;font-size: 20px;line-height: 30px;text-align: center;cursor: pointer;border-radius: 30px;'>" +
"   <div id='GoTopIcon' style='width: 30px!important;height: 30px!important;line-height: 30px!important;font-size: 25px !important;font-family: Helvetica!important;' title='回到顶部'>⇡</div>"+
"</a>"][0])
GM_addStyle([
'#tab-container             { display: flex; border-bottom: 2px solid #dee2e6; margin: 5px 5px 10px 5px; }'+
'.my-tab                    { flex: 1; text-align: center; padding: 2px 0; font-size: 16px; font-weight: bold; cursor: pointer; border-radius: 8px 8px 0 0; transition: all 0.2s ease; line-height: normal !important; border: 1px solid transparent; border-bottom: none; margin-bottom: -2px; }'+
'.my-tab.tab-active         { background-color: #228be6; color: #ffffff; border-color: #228be6; }'+
'.my-tab:not(.tab-active)   { background-color: #f1f3f5; color: #495057; }'+
'.my-tab:not(.tab-active):hover { background-color: #e9ecef; }'+
'#downIcon img               { width: 90%!important; height: 90%!important; margin: 5%!important; padding: 0!important; line-height: 90%!important;}'+
'#Allurl                     { display: flex; justify-content: flex-end; align-items: center; width: 94%; height: 20px; box-sizing: border-box; padding-right: 10px; padding-top: 4px; }'+
'#Allurl>span                { font-size: 16px; margin: 0 5px; cursor: pointer; }'+
'#MyUrls div,#MyUrls input   { box-sizing: content-box!important;line-height: 100%; }'+
'.MyUrls hr                  { background: #837b7b; border-color: #837b7b; border-width: 0;height: 1px;margin: 0; padding: 0; border: none !important; }'+
'.MyNR                       { max-height: 465px; overflow-y: auto; }'+
'.MyNR>div                   { display: none }'+
'.MyNR div[class^=No-isUrl]  { width: 30px;text-align: right;display: inline-block; height: 30px;line-height: 30px !important;}'+
'.MyNR input[class^=downUrl] {     pointer-events: auto !important; opacity: 1 !important; cursor: text !important;font-size: 12px;width: 145px;display: inline-block;margin: 0px;height: 22px;border: 1px solid black;border-radius: 5px;padding: 0 5px;background: white;color: #000000 !important;}'+
'.MyNR input[class^=downName]{ font-size: 12px;width: 35px;display: inline-block;margin: 0px;height: 22px;border: 1px solid black;border-radius: 5px;padding: 0 5px;background: white;color:black;}'+
'.But                        { margin-left: 5px;height:20px;width: 40px;padding: 0;border: none;background: #228be6;color: #ffffff;border-radius:10px;cursor: pointer;line-height: 20px!important;font-size: 10px;text-align: center; }'+
'.MyNR div[class^=rmUrl]     { display: inline-block;cursor: pointer;color:red; width: 30px; height: 30px;line-height: 30px !important; }'+
'#giegei717dplayer .dplayer-controller button{ background: black; }'+
'.dplayer-controller .dplayer-bar-wrap .dplayer-bar { height: 15px!important;top: -10px!important;}'+
'.dplayer-controller .dplayer-bar-wrap .dplayer-bar>[class^=dplayer-] { height: 15px!important;}'+
'.dplayer-controller .dplayer-bar-wrap .dplayer-bar .dplayer-played .dplayer-thumb { height: 22px!important; width: 22px!important;}'
][0]);
$(".MyNR div").append(["<div class='urlnone' style='height: 22px;color: red;padding: 9px 0 0 20px;font-size: 15px;'> 暂时没有嗅探到资源</div>"+
"<div class='downloadUrl' style='height: 31px; line-height: 30px;'>"+
"<hr    class='urlnone' > " +
"<div   class='No-isUrl'> 0、</div>"+
"<input class='downUrl'         autocomplete='on'   placeholder='请输入要下载的资源链接：' title='自定义资源下载项'> "+
"<input class='downName'        style=' width: 125px;'  placeholder='请输入文件名(下载用)' title='默认文件名为当前页面标题'>"+
"<div   class='But SaveUrl'     style='display: inline-block; '>下载</div>"+
"<div   class='But StopSaveUrl' style='display: none;         '>0%</div>"+
"<div   class='But playUrl'     style='display: inline-block; '>播放</div>&nbsp"+
"<div   class='rmUrl_input'     title='清空'                   >&nbspx&nbsp</div>"+
"</div>"][0])
var ad = 0,angle = 0;
$("#MyUpDown").click(function () {
var that = $(this)
$("#MyUrls").slideToggle("slow",function(){
if (mn=="1"||mn==1){
}else {
$("#redPoint").css("display","none")
};        mn = -mn;
});
if (mn=="1"||mn==1){
}
var a = angle
var setXZ = setInterval(function(){
$("#MyUpDown #downIcon").css( 'transform', 'rotate('+a+'deg)');
if ( a>=angle+45 ){ stop_XZ() }
},5);
function stop_XZ(){ clearInterval(setXZ); angle += 45 };
if(ad==0){
FirstOpen()
ad = 99;
}
})
$(window).click(function (e) {
var x =$(e.target).is('#MyUrls *,#MyUpDown *,#GoTop *,#MyUrls,#MyUpDown,#GoTop')
if( !x && $("#MyUrls").css("display")!="none" && $("#MyUpDown").css('pointer-events') != 'none'){
$("#MyUpDown").click()
$("#MyUpDown").css( 'pointer-events','none')
setTimeout(function(){ $("#MyUpDown").css( 'pointer-events','all') },500)
}
})
$("#tab-container > .my-tab").click(function (){
$(this).addClass('tab-active').siblings().removeClass('tab-active');
$('.MyNR>div').css('display', 'none').attr('id','');
$('.MyNR .'+$(this).attr('id')).css('display', 'block').attr('id','My_VorA');
});
$("#MyVideo").addClass('tab-active');
$("#MyVideo").click();
$("#Alldownload").click(function (){
$('#My_VorA .isUrl').each(function(){
$(this).find("div.But:nth-last-of-type(4)").click()
})
layer.msg("开始下载")
})
$("#Allcopy").click(function (){
var urlss ="";
$('#My_VorA .isUrl').each(function(){
urlss = urlss + $(this).find("[class^=downUrl]").attr('title').trim()+ "\n\n"
})
GM_setClipboard(urlss);
layer.msg("已复制")
})
window.onload = ()=>{
if(GM_getValue("checked_n", 1) == set['checked_n']){
$('body :not(body)').css('user-select','auto')
$('body #MyUrls[class=jianbian]').css('user-select', 'none')
document.onselectstart = function(){
event.returnValue = true;
return true;
}
document.oncopy = function(){
event.returnValue = true;
return true;
}
document.onbeforecopy = function(){
event.returnValue = true;
return true;
}
document.onkeydown = function(){
event.returnValue = true;
return true;
}
document.onkeyup = function(){
event.returnValue = true;
return true;
}
document.onkeypress = function(){
event.returnValue = true;
return true;
}
function MyCopy(event){
var text = window.getSelection().toString()
if ( text != undefined && text != null && text.trim() != '') {
navigator.clipboard.writeText(text)
.then(() => { layer.msg("已复制");console.log("已复制，由 copy 触发") })
.catch((error) => {  GM_setClipboard( text );})
}
}
function MyKeydown(event){
var text = window.getSelection().toString()
if ( (event.keyCode == 67 || event.keyCode == 88) && event.ctrlKey && text != undefined && text != null && text.trim() != '') {
navigator.clipboard.writeText(text)
.then(() => { layer.msg("已复制");console.log("已复制，由 keydown 触发") })
.catch((error) => {  GM_setClipboard( text );})
}
}
$(window).keydown( MyKeydown(event) ).on("copy", function(event){ MyCopy(event)} )
var set$ = setInterval(function(){
try {
$("body :not(button,input[type=button])").unbind("keypress keyup keydown copy").off( "keypress keyup keydown copy")
stopSet()
} catch(err) {
unsafeWindow.$ = $;
}},1000);
function stopSet(){ clearInterval(set$); };
}
}
$("#Alldel").click(function (){
$('#My_VorA .isUrl').remove()
GM_D.forEach(function(item){
item.forEach(function(i){
i.abort()
})
})
layer.msg("已清除")
})
$("#LupinStart").click( function (){
var constraints ={
audio:{
echoCancellation: true,
autoGainControl: true,
noiseSuppression:true
},
surfaceSwitching: "include",
video: {
frameRate: { ideal: 30},
width: { ideal: 1920 },
height: { ideal: 1080 },
}
};
var time = 0;
var opts = {
onstart: function onStart() {
time = new Date().getTime()
console.log('Recorder is started'+"\n"+'开始录屏');
$("#LupinStart").css('display','none')
$("#LupinStop").css('display','inline-block')
},
onstop: function onStop(blob) {
time = new Date().getTime() - time;
console.log('Recorder is stop'+"\n"+'录屏结束'+'\n'+'时长：'+time);
stream.getTracks().forEach((track) => track.stop());
var link = document.createElement("a");
link.href = window.URL.createObjectURL(new Blob([blob]))
link.download = "录屏 "+ new Date().toLocaleString().replaceAll("/",'-').replaceAll(":",'-') +".mp4";
link.click();
link.remove();
$("#LupinStop").css('display','none')
$("#LupinStart").css('display','inline-block')
},
mimeType:  "video/webm; codecs=h264"
};
try {
window.rec = new QBMediaRecorder(opts);
} catch(err) {
console.log(err)
}
navigator.mediaDevices.getDisplayMedia(constraints).then((stream) => {
window.stream = stream
rec.start(stream);
});
})
$("#LupinStop").click(function (){
rec.stop()
})
$(".downUrl").on( 'input' ,function (){
$(this).attr("title",$(this).val())
})
$(".downName").on( 'input' ,function (){
$(this).attr("title",$(this).val())
})
$(".rmUrl_input").click(function (){
$(this).prevAll("input").val('')
$(this).prevAll(".downUrl").attr("title","自定义视频下载项")
$(this).prevAll(".downName").attr("title","默认文件名为当前页面标题")
})
GoTop()
window.onscroll = function(){ GoTop()}
function GoTop(){
var t = document.documentElement.scrollTop || document.body.scrollTop;
if( t >= 100 ) {
$("#GoTop").css("display","block")
} else {
$("#GoTop").css("display","none")
}
}
$(".MyNR  .playUrl").click(function (){
var url = $(this).prevAll(".downUrl").attr("title")
var type = $(this).prevAll(".downUrl").data('type')
if(url == undefined || url.trim()=="" || url.trim().length == 0 || url.trim().split(".").filter(function(item){return item.trim() != "";}).length < 2){
layer.msg("无有效链接")
}else{
dplayerUrl(url,0,type)
$(".But:nth-last-of-type(2)").text('播放')
$(this).text("播放中")
}
})
function functionAll(u,VorA="MyVideo"){
$(".MyNR ."+VorA+" .GoUrl"+u).click(function (){
var url = $(this).prevAll(".downUrl"+u).attr("title")
var link = document.createElement('a');
link.href = url;
link.target="_blank";
link.click();
link.remove();
})
$(".MyNR ."+VorA+" .CopyUrl"+u).click(function (){
var url = $(this).prevAll(".downUrl"+u).attr("title")
GM_setClipboard(url);
var aux = document.createElement("input");
aux.setAttribute("value", url);
document.body.appendChild(aux);
aux.select();
document.execCommand("copy");
document.body.removeChild(aux);
$(this).text("已复制")
})
$(".MyNR ."+VorA+" .playUrl"+u).click(function (){
var url = $(this).prevAll(".downUrl"+u).attr("title")
var type = $(this).prevAll(".downUrl"+u).data('type')
if(url == undefined || url.trim()=="" || url.trim().length == 0 || url.trim().split(".").filter(function(item){return item.trim() != "";}).length < 2){
layer.msg("无有效链接")
}else{
var ui = u==''? 0 : u
dplayerUrl(url,ui,type)
$(".But:nth-last-of-type(2)").text('播放')
$(this).text("播放中")
}
})
$(".MyNR ."+VorA+" .rmUrl"+u).click(function (){
var num = $(this).prevAll('.StopSaveUrl'+u).data('num')
if(num != undefined){
GM_D[num].forEach(function(item){
item.abort()
})
}
$(this).parent(".isUrl").remove()
var list = $('.isUrl')
list.each(function(i){
$(this).children("#No-isUrl").text(i+1+'、')
})
})
$(".MyNR ."+VorA+" .downName"+u).on( 'input' ,function (){
$(this).attr("title",$(this).val())
})
$(".MyNR input").dblclick( function () {
this.select()
})
$(".MyNR ."+VorA+" .SaveUrl"+u).click(function (){
var that = $(this)
var url = $(that).prevAll(".downUrl"+u).val()
if(url==undefined||url.trim()==''){
url = $(that).prevAll(".downUrl"+u).attr('title')
if(url==undefined||url.trim()==''||url.trim()=="自定义资源下载项"){
layer.msg("无有效链接");
return;
}
}
var name = $(that).prevAll(".downName"+u).val()
if(name==undefined||name.trim()==""){
name = $('title').text()
if(name==undefined||name.trim()==""){
name = url.split("/").pop().split("?")[0]
if(name==undefined||name.trim()==""){
name = "文件未命名"
}
}
}
name = name.replaceAll(/\s+/ig," ").trim().replace(/(\.mp4)*$/igm,"")
if( $(that).parents('.MyNR>div').find('.isUrl').length>5){
name = $(that).prevAll(".No-isUrl").text().trim() + name
}
$(that).css("display","none").next('.StopSaveUrl'+u).css("display","inline-block").text("解析中");
var request = [];
var head_i = $(that).data('head_i')
var head = $(that).data('head')
console.log(head)
delete head['Range'];
delete head['Cache-Control']
var blob = [];
var loadSize = [];
var xhrs = 0
var num = -1
var href = $(that).data('head_href')
var origin = head_i == 1 ? location.origin : $(that).data('head_origin')
var Length =$(that).data('length')
var Headers = $(that).data('headers')
var Type = $(that).data('type')
if(VorA == "MyVideo"){
name = name+".mp4"
if( Length==null||Length==undefined||Length==""||Length<=0 && Type !="hls"){
console.log("mp4视频单线程下载中ing。")
mp4Download(url)
return;
}
if( Type == "hls"){
m3u8Download(url)
}else{
console.log("mp4视频多线程下载中ing。")
var RangeSize = parseInt((Length/ xcNum).toFixed(0))
for(var i=0,z=0;i<Length;i=i+RangeSize,z++){
var range_start=i,range_end=i+RangeSize-1;
if (range_end + 1 >=Length) {range_end = Length}
DownloadThread( z , range_start , range_end)
}
num =GM_D.push(request)
$(that).next('.StopSaveUrl'+u).data('num',num-1)
function DownloadThread( z, range_start , range_end){
function onprogress (event){
loadSize[z] = event.loaded;
var all_length =0;
loadSize.forEach( function(item){
all_length = all_length + item
});
var loaded = ( parseFloat( all_length / Length * 100)).toFixed(1);
if(loaded <= 100){
$(that).next(".StopSaveUrl"+ u ).text( loaded +"%");
console.log(u+"、线程 "+z+" ： 已下载"+ event.loaded +" 总" +event.total);
}
}
head.Range = "bytes=" +range_start +"-"+ range_end;
request[z] = GM_xmlhttpRequest({
method: "GET",
url: url,
fetch: false,
responseType: "arraybuffer",
headers: head ,
onprogress: onprogress ,
onload: function(response) {
blob[z] = new Blob([response.response], { type: 'video/mp4' });
var x=0,y=0;
loadSize.forEach(function(item){
x = x + item
});
blob.forEach(function(item){
y = y + item.size
});
console.log(u +"、线程 "+z+" ： 下载结束 下完线程的文件大小："+ y +" 已下载的文件大小："+ x +" 总："+ Length);
if (y >= Length) {
var link = document.createElement("a");
link.href = window.URL.createObjectURL(new Blob(blob, { type: 'video/mp4' }));
link.download = name;
link.click();
link.remove();
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+ u).css("display","none").text("0%");console.warn(u +"、文件下载完成：" +name)
}
},
onabort: function(){
console.log("abort！");
},
onerror: function(x) {
console.log("error！更换线路ing");
request.forEach(function(item){
item.abort()
});
mp4Download(url);
var numi = parseInt( $(that).next(".StopSaveUrl"+u ).data("num") );
GM_D[numi] = request;
},
});
}
}
}else if(VorA == "MyAudio"){
name = name+".mp3"
mp4Download(url)
}
function mp4Download(url){
console.log("资源单线程下载中ing。")
head['If-Modified-Since'] = '0';
request.push(GM_download({
url: url,
name: name,
headers: head,
onprogress : function (event) {
if (event!=null) {
var loaded = parseFloat(event.loaded / event.total * 100).toFixed(2);
if(loaded >= 100){
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none");
}else{
$(that).css("display","none").next(".StopSaveUrl"+u).css("display","inline-block").text(loaded+"%");
console.log(u+"、单线程： 已下载"+event.loaded+" 总"+event.total+ " 比 "+loaded +"%");
}
}
},
onload : function () {
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none").text("0%");
console.warn(u+"、文件下载完成："+name)
},
onerror : function (x) {
console.log(x)
$(that).text("错误").css("display","inline-block").attr("title","下载出错。").next(".StopSaveUrl"+u).css("display","none").text("0%");
}
}))
num =GM_D.push(request)
$(that).next('.StopSaveUrl'+u).data('num',num-1);
}
function m3u8Download(url){
console.log("m3u8解析下载中ing。")
GM_xmlhttpRequest({
method: "GET",
url: url,
headers: head,
onerror: function(x) {
console.log("m3u8 GET出错onerror")
$(that).text("错误").css("display","inline-block").attr("title","下载出错。").next(".StopSaveUrl"+u).css("display","none").text("0%");
},
onload: function(response) {
var err = []
var tsNum=0
var tsS = 0
var tsi =0
var tsLength
var list0
var IV="",keyData=null;
async function syncRequest(url) {
var r = '';
head['If-Modified-Since'] = '0'
await GM.xmlHttpRequest({
method: "GET",
url: url ,
headers: head,
responseType: "arraybuffer",
}).then((value) => {console.log(value);r = value.response; }).catch(e => {console.error(e);return null});
return r;
}
function jiemi(pwdBlob){
IV = IV==null ? keyData : IV ;
return aesDecryptArrayBuffer(keyData, IV, pwdBlob)
function aesDecryptArrayBuffer(key, iv, encryptedArrayBuffer) {
var encryptedWords = CryptoJS.lib.WordArray.create(encryptedArrayBuffer);
if (typeof iv === 'string') { iv = stringToArrayBuffer(iv) }
if (typeof key === 'string') { key = stringToArrayBuffer(key) }
iv = CryptoJS.lib.WordArray.create(iv);
key = CryptoJS.lib.WordArray.create(key);
var decryptedWords = CryptoJS.AES.decrypt(
{ ciphertext: encryptedWords },
key,
{
iv: iv,
mode: CryptoJS.mode.CBC,
padding: CryptoJS.pad.Pkcs7
}
);
var decryptedBytes = CryptoJS.enc.Base64.stringify(decryptedWords);
var decryptedArrayBuffer = base64ToArrayBuffer(decryptedBytes);
return decryptedArrayBuffer;
}
function pad(key) {
var x = 0b0
while (key.length % 16 !== 0) {
key += 0b0;
}
return key;
}
function base64ToArrayBuffer(base64) {
var binaryString = window.atob(base64);
var len = binaryString.length;
var bytes = new Uint8Array(len);
for (var i = 0; i < len; i++) {
bytes[i] = binaryString.charCodeAt(i);
}
return bytes.buffer;
}
function arrayBufferToBase64(buffer) {
var binary = '';
var bytes = new Uint8Array(buffer);
var len = bytes.byteLength;
for (var i = 0; i < len; i++) {
binary += String.fromCharCode(bytes[i]);
}
return btoa(binary);
}
function stringToArrayBuffer(str) {
let encoder = new TextEncoder();
return encoder.encode(str).buffer;
}
}
function downTs(list,tsUrl,i,status){
head['If-Modified-Since'] = '0';
request[i] = GM_xmlhttpRequest({
method: "GET",
url: tsUrl,
headers: head,
responseType: "arraybuffer",
onloadstart: function(){
},
onload: function(response) {
var buf = response.response
blob[i] = buf
if( status == "key"){
var setjm = setInterval(function(){
if(keyData != null ){
stopjm()
blob[i] = ( new Blob([jiemi(response.response)], { type: 'video/mp4' }) )
}},50);
function stopjm(){ clearInterval(setjm); };
}else{
blob[i] = ( new Blob([response.response], { type: 'video/mp4' }) )
}
list0.splice(list0.indexOf(tsUrl),1)
if (list0.length>0) {
tsNum = parseFloat(tsNum) + 1 / tsS * 100;
tsNum = tsNum >100 ? 100 : parseFloat(tsNum).toFixed(2);
tsi = tsi+1
console.log(u+"、已下完的视频切片数："+ tsi +" 总数："+ tsS);
$(that).next(".StopSaveUrl"+u).text(tsNum+"%");
if(list.length > 0 ){
downTs(list,list.shift(),i+1,status)
}
}else {
$(that).next(".StopSaveUrl"+u).text("100%");
var is = true;
try {
var sab = new SharedArrayBuffer(1);
} catch(err) {
console.log( err.message +"\n 浏览器不支持SharedArrayBuffer")
is = false
}
var link = document.createElement("a");
if(GM_getValue("ffmpeg_n", 1) == set['ffmpeg_n'] && is){
(async () => {
try {
FFmpeg;
} catch(err) {
console.log( err.message +"\n 没有加载FFmpeg");
await $.ajax({
async: false,
url: "https://unpkg.com/@ffmpeg/ffmpeg@0.10.0/dist/ffmpeg.min.js",
dataType: "script"
});
}
$(that).next(".StopSaveUrl"+u).text("转码中");
const { createFFmpeg, fetchFile } = FFmpeg;
const ffmpeg = createFFmpeg({
log: true,
progress: ({ ratio }) => {
tsNum = (ratio * 100.0).toFixed(2)
$(that).next(".StopSaveUrl"+u).text(tsNum+"%").attr("title",'转码中');
},
});
console.log( '正在加载 ffmpeg-core.js');
await ffmpeg.load();
console.log('开始转码');
ffmpeg.FS('writeFile', 'video.ts', await fetchFile(new Blob(blob)) );
await ffmpeg.run('-i', 'video.ts' ,'output.mp4');
console.log('转码完成');
const data = ffmpeg.FS('readFile', 'output.mp4');
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none").attr("title","下载中");
link.href = window.URL.createObjectURL(new Blob([data.buffer], { type: 'video/mp4' }));
link.download = name;
link.click();
link.remove();
ffmpeg.exit()
})();
}else{
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none").attr("title","下载中");
link.href = window.URL.createObjectURL(new Blob(blob, { type: 'video/mp4' }));
link.download = name;
link.click();
link.remove();
}
console.warn(u+"、文件下载完成："+name)
}
},
onabort: function(){
console.log("abort！");
},
onerror: function(x) {
console.log("ts GET出错onerror!")
console.log(x)
if (err<10){
err = err+1
downTs(list,tsUrl,i)
}else{
err = 0
$(that).text("错误").css("display","inline-block").attr("title","下载出错").next(".StopSaveUrl"+u).css("display","none").text("0%");
var num = $(that).next(".StopSaveUrl"+u).data("num")
GM_D[num].forEach(function(item){
item.abort()
})
}
}
});
}
var Ts = response.responseText.trim()
var TsStart = Ts.split(/(#EXTINF[^\n]*|#EXT-X-STREAM-INF[^\n]*)/)[0];
if(/^#EXTM3U/.test(TsStart)){
console.log("m3u8解析中")
var num1,num2,ad_ts
if(GM_getValue("ad_n", 1)== set['ad_n']){
while( Ts.search(/#EXT-X-DISCONTINUITY/i) != -1 ){
num1 = Ts.search(/#EXT-X-DISCONTINUITY/i);
Ts = Ts.replace(/#EXT-X-DISCONTINUITY/i,'这是要去除的部分')
num2 = Ts.search(/#EXT-X-DISCONTINUITY/i);
ad_ts = num2 != -1 ? Ts.slice( num1,num2+20) : Ts.slice(num1)
Ts = Ts.replace(ad_ts,"")
if( Ts.search(/#EXT-X-DISCONTINUITY/i) == -1){
break
}
}
}
Ts = Ts.replaceAll(/^#(?!(EXTINF[^\n]*|EXT-X-STREAM-INF[^\n]*))[^\n]*/img,"").trim().replaceAll(/\n#/img,'??#').split('??')
Ts = Ts.filter(function(item){
return item.trim() != "";
});
var status = "",bool = "false",mapURI = "false",keyUrl,keytext;
TsStart.split("\n").forEach(function(item){
if(/#EXT-X-KEY/.test(item.trim())){
status = item.match(/METHOD=[\w-]{4,10}/im)[0]
if( status!=undefined && status!= null && status!=''){
status = status.replaceAll(/METHOD=/igm,'').trim()
if (status=='None'||status=="NONE"||status=='none'||status==''){
status = ""
}else{
status = "key"
keytext = item.match(/URI="[^"'\s]*"/i)[0].replaceAll(/(URI="|")/ig,'').trim()
keyUrl = keytext
if(/[\w]*\.key/.test(keyUrl)){
if( /^http[s]?:\/\/\w*\./.test(keyUrl)){
keyUrl = keyUrl
}else if( /^\/\/\w*\./.test(keyUrl) | /^\w*\.\w*/.test(keyUrl)){
keyUrl = (new URL(url)).protocol + '//'+keyUrl.replaceAll(/(^\/\/)/ig,'')
}else{
tsUrl1 = url.split("?")[0].split("/");
tsUrl1.pop();
keyUrl = tsUrl1.join("/")+"/"+keyUrl.replaceAll(/\s*/img,"")
}
}
IV = item.match(/IV=[\wx]*/i)[0]
IV = IV==null|IV==undefined|IV=="" ? null : IV.replaceAll(/(IV=)/ig,'').trim()
}
return;
}
}
if(/#EXT-X-TARGETDURATION/.test(item.trim())){
bool = "true"
}
if(/#EXT-X-MAP:URI=/.test(item.trim())){
mapURI = Ts[0].split("\n")[0]+"\n"+item.replaceAll('#EXT-X-MAP:URI=','').replaceAll('"','').replaceAll("'","").trim()
Ts.unshift(mapURI)
var list = []
Ts.forEach(function(item,i){
list.push(item)
list.push(mapURI)
})
Ts = list.slice();
}
})
if(status == "key"){
if( /^(http:|https:)?(\/{0,2}([^\.\s\/]*\.){1,2}[\w]{1,8})/.test(keyUrl) ){
keyData = syncRequest(keyUrl).then(val => {
keyData = val
console.log( "m3u8加密,启用解密")
}).catch(e => {
keyData = keytext
console.log(e)
console.log( "m3u8加密,解密困难，尝试中")
})
}else{
$(that).text("错误").css("display","inline-block").attr("title","m3u8加密，暂时无法解决。").next(".StopSaveUrl"+u).css("display","none").text("0%");
console.log("m3u8加密，暂时无法解决。")
layer.msg("m3u8加密，暂时无法解决。", {icon: 2});
return;
}
}
var tsUrl,tsUrl1;
Ts.forEach(function(item,i){
if(/^(#EXTINF[^\n]*|#EXT-X-STREAM-INF[^\n]*)/.test(item.trim())){
tsUrl = item.trim().match(/\n.*/img)[0].trim()
if(/^(http:|https:)/.test(tsUrl)){
tsUrl = item.trim()
}else if( /^(\/{0,2}([^\.\s\/]*\.){1,3}[\w]{1,8}(:[\d]{1,5})?)\/.*/.test(tsUrl) ){
tsUrl = href.trim().match(/^(http:|https:)/im)[0]+"//"+tsUrl.replace(/^(\/{0,2})/img,"")
}else if( /^(\/)/.test(tsUrl)){
tsUrl1 = url.replace("://",":\\"). split(/\//)[0].replace(":\\","://")
tsUrl1 = tsUrl1 + tsUrl.replaceAll(/\s*/img,"")
tsUrl = item.trim().replaceAll(tsUrl.trim(),tsUrl1)
}else{
tsUrl1 = url.split("?")[0].split("/");
tsUrl1.pop();
tsUrl1 = tsUrl1.join("/")+"/"+tsUrl.replaceAll(/\s*/img,"")
tsUrl = item.trim().replaceAll(tsUrl.trim(),tsUrl1)
}
Ts[i] = tsUrl
}
})
if(bool == "true"){
console.log("m3u8没有嵌套，直接解析。")
Ts.forEach(function(item,i){
Ts[i] = item.trim().match(/\n.*/img)[0].trim();
});
tsLength = Ts.length;
tsS = tsLength;
var TssSize = parseInt( ( tsLength/ xcNum).toFixed(0) )
TssSize = TssSize < 1 ? 1 : TssSize;
list0 = Ts.slice();
for(var i=0,z=0;i < tsLength; i = i+TssSize, z++){
var range_start = i,range_end = i+TssSize;
if (range_end > tsLength) {range_end = tsLength}
var tslist = Ts.slice(range_start,range_end);
downTs(tslist, tslist.shift(),i,status)
}
num =GM_D.push(request)
$(that).next('.StopSaveUrl'+u).data('num',num-1);
}else{
console.log("这下边嵌套了m3u8。")
var maxP='0x0',maxUrl='';
Ts.forEach(function(item,i){
tsUrl = item.split("\n",2)
if( /RESOLUTION=\d+\D\d+/igm.test( tsUrl[0] )){
var P = tsUrl[0].match(/RESOLUTION=\d+\D\d+/igm)[0].match(/\d+\D\d+/igm)[0]
if( maxP.split(/\D/).reduce(function(val1,val2){return val1*val2}) < P.split(/\D/).reduce(function(val1,val2){return val1*val2}) ){
maxUrl = tsUrl[1] ;
maxP = P
}
}else{
maxUrl = tsUrl[1]
return;
}
})
GM_xmlhttpRequest({
method: "GET",
url: maxUrl,
onerror: function(x) {
$(that).text("错误").css("display","inline-block").attr("title","下载出错。").next(".StopSaveUrl"+u).css("display","none").text("0%");
console,log(x)
},
onload: function(response) {
url = maxUrl
var Ts = response.responseText.trim()
var TsStart = Ts.split(/(#EXTINF[^\n]*)/)[0];
if(/^#EXTM3U/.test(TsStart)){
console.log("嵌套m3u8解析中")
var num1,num2,ad_ts
if(GM_getValue("ad_n", 1)== set['ad_n']){
while( Ts.search(/#EXT-X-DISCONTINUITY/i) != -1 ){
num1 = Ts.search(/#EXT-X-DISCONTINUITY/i);
Ts = Ts.replace(/#EXT-X-DISCONTINUITY/i,'这是要去除的部分')
num2 = Ts.search(/#EXT-X-DISCONTINUITY/i);
ad_ts = num2 != -1 ? Ts.slice( num1,num2+20) : Ts.slice(num1)
Ts = Ts.replace(ad_ts,"")
if( Ts.search(/#EXT-X-DISCONTINUITY/i) == -1){
break
}
}
}
Ts = Ts.replaceAll(/^#(?!(EXTINF[^\n]*))[^\n]*/img,"").trim().replaceAll(/\n#/img,'??#').split('??')
Ts = Ts.filter(function(item){
return item.trim() != "";
});
var status = "",bool = "false",mapURI = "false",keyUrl,keytext;
TsStart.split("\n").forEach(function(item){
if(/#EXT-X-KEY/.test(item.trim())){
status = item.match(/METHOD=[\w-]{4,10}/im)[0]
if( status!=undefined && status!= null && status!=''){
status = status.replaceAll(/METHOD=/igm,'').trim()
if (status=='None'||status=="NONE"||status=='none'||status==''){
status = ""
}else{
status = "key"
keytext = item.match(/URI="[^"'\s]*"/i)[0].replaceAll(/(URI="|")/ig,'').trim()
keyUrl = keytext
if(/[\w]*\.key/.test(keyUrl)){
if( /^http[s]?:\/\/\w*\./.test(keyUrl)){
keyUrl = keyUrl
}else if( /^\/\/\w*\./.test(keyUrl) | /^\w*\.\w*/.test(keyUrl)){
keyUrl = (new URL(url)).protocol + '//'+keyUrl.replaceAll(/(^\/\/)/ig,'')
}else{
tsUrl1 = url.split("?")[0].split("/");
tsUrl1.pop();
keyUrl = tsUrl1.join("/")+"/"+keyUrl.replaceAll(/\s*/img,"")
}
}
IV = item.match(/IV=[\wx]*/i)[0]
IV = IV==null|IV==undefined|IV=="" ? null : IV.replaceAll(/(IV=)/ig,'').trim()
}
return;
}
}
if(/#EXT-X-MAP:URI=/.test(item.trim())){
mapURI = Ts[0].split("\n")[0]+"\n"+item.replaceAll('#EXT-X-MAP:URI=','').replaceAll('"','').replaceAll("'","").trim()
Ts.unshift(mapURI)
var list = []
Ts.forEach(function(item,i){
list.push(item)
list.push(mapURI)
})
Ts = list.slice();
}
})
if(status == "key"){
if( /^(http:|https:)?(\/{0,2}([^\.\s\/]*\.){1,2}[\w]{1,8})/.test(keyUrl) ){
keyData = syncRequest(keyUrl).then(val => {
keyData = val
console.log( "m3u8加密,启用解密")
}).catch(e => {
keyData = keytext
console.log(e)
console.log( "m3u8加密,解密困难，尝试中")
})
}else{
$(that).text("错误").css("display","inline-block").attr("title","m3u8加密，暂时无法解决。").next(".StopSaveUrl"+u).css("display","none").text("0%");
console.log("m3u8加密，暂时无法解决。")
layer.msg("m3u8加密，暂时无法解决。", {icon: 2});
return;
}
}
var tsUrl,tsUrl1;
Ts.forEach(function(item,i){
if(/^(#EXTINF[^\n]*|#EXT-X-STREAM-INF[^\n]*)/.test(item.trim())){
tsUrl = item.trim().match(/\n.*/img)[0].trim()
if(/^(http:|https:)/.test(tsUrl)){
tsUrl = item.trim()
}else if( /^(\/{0,2}([^\.\s\/]*\.){1,3}[\w]{1,8}(:[\d]{1,5})?)\/.*/.test(tsUrl) ){
tsUrl = href.trim().match(/^(http:|https:)/im)[0]+"//"+tsUrl.replace(/^(\/{0,2})/img,"")
}else if( /^(\/)/.test(tsUrl)){
tsUrl1 = url.replace("://",":\\"). split(/\//)[0].replace(":\\","://")
tsUrl1 = tsUrl1 + tsUrl.replaceAll(/\s*/img,"")
tsUrl = item.trim().replaceAll(tsUrl.trim(),tsUrl1)
}else{
tsUrl1 = url.split("?")[0].split("/");
tsUrl1.pop();
tsUrl1 = tsUrl1.join("/")+"/"+tsUrl.replaceAll(/\s*/img,"")
tsUrl = item.trim().replaceAll(tsUrl.trim(),tsUrl1)
}
Ts[i] = tsUrl
}
})
Ts.forEach(function(item,i){
Ts[i] = item.trim().match(/\n.*/img)[0].trim();
});
tsLength = Ts.length;
tsS = tsLength;
var TssSize = parseInt( ( tsLength/ xcNum).toFixed(0) )
TssSize = TssSize < 1 ? 1 : TssSize;
list0 = Ts.slice();
for(var i=0,z=0;i < tsLength; i = i+TssSize, z++){
var range_start = i,range_end = i+TssSize;
if (range_end > tsLength) {range_end = tsLength}
var tslist = Ts.slice(range_start,range_end);
downTs(tslist, tslist.shift(),i,status)
}
num =GM_D.push(request)
$(that).next('.StopSaveUrl'+u).data('num',num-1);
}else{
var blob = new Blob([response.response], { type: 'video/mp4' })
if( blob.size< 1024*1024/2 ){
$(that).text("错误").css("display","inline-block").attr("title","URL链接异常").next(".StopSaveUrl"+u).css("display","none").text("0%");
console.log("URL链接异常")
layer.msg("URL链接异常，请检查链接后重试", {icon: 2});
return;
}
var link = document.createElement("a");
link.href = window.URL.createObjectURL(blob );
link.download = name;
link.click();
link.remove();
$(that).text("下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none").text("0%");
}
}
})
}
}else{
var link = document.createElement("a");
link.href = window.URL.createObjectURL(new Blob([response.response], { type: 'video/mp4' }));
link.download = name;
link.click();
link.remove();
$(that).text("已下载").css("display","inline-block").attr("title","下载").next(".StopSaveUrl"+u).css("display","none").text("0%");
}
}
})
}
})
$(".MyNR ."+VorA+" .StopSaveUrl"+u).click(function (){
var num = $(this).data("num")
GM_D[num].forEach(function(item){
item.abort()
})
$(this).data("num","").css("display","none").text("0%").prev(".SaveUrl"+u).text("继续").attr("title","下载中断").css("display","inline-block");
})
}
try {
GM.webRequest()
GM_webRequest([
{ selector: '*://*/*.m3u8*', action: { redirect: { from: "(.*)", to: "\$1" } } },
{ selector: '*://*/*m3u8*',  action: { redirect: { from: "(.*)", to: "\$1" } } },
], function(info, message, details) {
var z = details.url;
if(z == $('#MyUrls .downUrl').val().trim() ){ return; }
if(z !=undefined && z.trim()!="" ){
addUrl( z )
}
});
if( location.host == 'www.douyin.com' ){
GM_webRequest([
{ selector: 'https://v3-web-prime.douyinvod.com/video/*', action: { redirect: { from: "(.*)", to: "\$1" } } },
{ selector: 'https://v26-web-prime.douyinvod.com/video/*', action: { redirect: { from: "(.*)", to: "\$1" } } },
{ selector: 'https://www.douyin.com/aweme/v1/play/?file_id=*', action: { redirect: { from: "(.*)", to: "\$1" } } },
], function(info, message, details) {
var z = details.url.trim();
if(z == $('#MyUrls .downUrl').val().trim() ){ return; }
if(z !=undefined && z.trim()!="" ){
addUrl( z )
}
});
}
} catch(err) {
console.log("当前浏览器不支持 GM_webRequest()");
}
if( location.host == 'www.bilibili.com' && ( /https:\/\/www\.bilibili\.com\/video\/BV\w*/i.test(location.href)||/https:\/\/www\.bilibili\.com\/bangumi\/play\/\w*/i.test(location.href) ) ){
window.addEventListener("pushState", function () {
biliVideo (1)
});
if (window.self == window.top) {
biliVideo (0)
}
}
var bili_url = ''
var New_bili_url=''
function biliVideo (i){
var set$ = setInterval(function(){
var videoData
switch( location.href.trim().match(/(www\.bilibili\.com\/(video|bangumi))/im)[0] )
{
case "www.bilibili.com/video":
videoData = unsafeWindow.__INITIAL_STATE__.videoData
var name = new URLSearchParams(window.location.search);
var page = name.get('p');
if(page == null||page ==undefined||page ==''){
page = 1
}
for (let i = 0; i < videoData.pages.length; i++) {
if (videoData.pages[i].page == page) {
New_bili_url = 'https://api.bilibili.com/x/player/playurl?avid='+ videoData.aid+'&cid='+ videoData.pages[i].cid+'&qn=120'
name = videoData.pages[i].part
if( name.trim() != $('.video-title').text().trim() ){
name = $('.video-title').text().trim()+" —— "+ name.trim()
}
break;
}
}
break;
case "www.bilibili.com/bangumi":
if(i==0){
videoData = playurlSSRData.result.play_view_business_info.episode_info
}else{
videoData = $($.get({url:location.href,async:false}).responseText).last().text()
videoData = JSON.parse(videoData).props.pageProps.dehydratedState.queries[0].state.data.result.play_view_business_info.episode_info
}
New_bili_url = 'https://api.bilibili.com/x/player/playurl?avid='+ videoData.aid+'&cid='+ videoData.cid+'&qn=120'
name = $('[class^=mediainfo_mediaTitle__]').text()+" ["+videoData.title+"] : "+videoData.long_title
break;
default:
break;
}
if( location.host == 'www.bilibili.com' && ( /https:\/\/www\.bilibili\.com\/video\/BV\w*/i.test(location.href)||/https:\/\/www\.bilibili\.com\/bangumi\/play\/\w*/i.test(location.href) ) && New_bili_url!=bili_url ){
bili_url = New_bili_url
stopSet()
GM_xmlhttpRequest({
method: "GET",
url: bili_url ,
headers: {'Referer': location.href,'If-Modified-Since': '0',"Cache-Control":"no-store"},
onerror: function(x) {
console.log("bili接口数据出错: url="+bili_url)
console.log(x)
},
onload: function(response) {
if( response.status>400){ return; }
var data = response.responseText;
New_bili_url = JSON.parse(data).data.durl[0].url;
url_lists.push( New_bili_url )
addUrl(New_bili_url, name )
}
})
}
},100);
function stopSet(){ clearInterval(set$); };
}
var _wr = function(type) {
var orig = history[type];
return function() {
var rv = orig.apply(this, arguments);
var e = new Event(type);
e.arguments = arguments;
window.dispatchEvent(e);
return rv;
};
};
unsafeWindow.history.pushState = _wr('pushState');
unsafeWindow.history.replaceState = _wr('replaceState');
function getNetworkRequsts(){
return performance.getEntriesByType("resource") .filter((entry) => {
return (entry.initiatorType === "audio"||entry.initiatorType === "video" || entry.initiatorType=== "xmlhttprequest" || entry.initiatorType=== "fetch");
});
}
var observer = new PerformanceObserver(perf_observer);
observer.observe({entryTypes: ["resource"]})
unsafeWindow.scriptsList = []
unsafeWindow.url_lists = []
function perf_observer(list,observer){
var z,m,length= 0;
length = $('.MyUrls .isUrl').length
var scripts =getNetworkRequsts()
scripts = scripts.filter(function(i){
return !scriptsList.includes(i);
} )
if(scripts.length<1){ scripts.push('') }
scripts.forEach(function (x,i) {
if( x != ""){
z = x.name.trim()
if($('.MyNR div.downloadUrl > input.downUrl').map(function() {return $(this).val();}).get().includes(z) ){console.log('此链接是0、框里的'); return; }
url_lists.push( z )
if( (/m3u8/i.test(z) && !/\.ts/i.test(z.replaceAll(/\?.*/g,''))) || /mp4\??.*/i.test(z) || /\.ogg\??.*/i.test(z) || /.*\.m4a\??.*/i.test(z) || /.*\.mp3\??.*/i.test(z) || !( /\.\w{1,5}$/i.test(z.replaceAll(/\?.*/g,'')) )){
if(z !=undefined && z.trim()!="" ){
var name = ""
switch( location.host )
{
case 'y.qq.com':
if(location.href== 'https://y.qq.com/n/ryqq/player' ){
name = $('.player_music__info').text()
}
break;
case 'www.iwara.tv':
name = '['+$('a[class=username]').attr('title') +'] '+ $('title').text()
break;
case 'www.douyin.com' :
default:
break;
}
addUrl( z,name)
}
}
}
$("video").each(function () {
var that = $(this)
if( that.parents("#giegei717dplayer").length!=0){  return; }
if(!/^blob:/i.test( that.attr('src') ) ){
z = that.attr('src')
if(z !=undefined && z.trim()!="" ){
var name = ""
switch( location.host )
{
case 'buyin.jinritemai.com' :
if( /https\:\/\/buyin\.jinritemai\.com\/dashboard\/merch-picking-library\/merch-promoting\?/i.test( location.href ) ){
var title =$(that).parents('[class^=index_module__contentCard____]').find('div[class^=index_module__authorInfo____]')
name = title.find('[class^=index_module__name____]').text()
var show = title.find('[class^=index_module__descLine____]').text()
name = name+' --- '+show
}
break;
default:
break;
}
addUrl( z,name)
}
}
})
$("audio").each(function () {
var that = $(this)
z = $(this).attr('src')
if( that.parents("#giegei717dplayer").length!=0){  return; }
if(z !=undefined && z.trim()!="" ){
var name = ""
if( /https:\/\/y\.qq\.com\/n\/ryqq\/player/i.test(location.href) ){ name = $('.player_music__info').text() }
addUrl( z,name)
}
})
$("source").each(function () {
if( $(this).parents("#giegei717dplayer").length!=0){ return; }
if($(this).attr('src')!=undefined && $(this).attr('src').trim()!='' && !/^blob:/.test($(this).attr('src')) ){
if(!/^(http:|https:)/.test($(this).attr('src'))){
z = location.href.split("://")[0] +':'+ $(this).attr('src')
}else{
z = $(this).attr('src')
}
addUrl( z,"" )
}
})
})
scriptsList = scriptsList.concat(scripts);
if($('.MyUrls .isUrl').length > length){
}
}
var ul_li = 0
window.addEventListener('message', function(event) {
var url = event.data
if( url.url== undefined || url.url == null || url.url ==""){ return; }
addUrl(url.url, url.name , url.href)
}, true)
if (window.self !== window.top && ($('#MyUpDown').css("display")!="none" || $('#MyUrls').css("display")!="none") ) {
$('#MyUpDown,#MyUrls').css("display","none")
}
unsafeWindow.url_info = []
unsafeWindow.urls = []
function addUrl( url ,name='',href = location.href){
if( url == undefined || url == null || url.length < 1){
console.log("addurl=null, return.")
return;
}
if (window.self != window.top) {
var message = { url:url,name:name, href:location.href };
window.parent.postMessage(message, "*");
return;
} else {
url = url.toString().trim()
if(/^(http:|https:)/.test(url)){
}else if( /^(\/{0,2}([^\.\s\/]*\.){1,3}[\w]{1,8}(:[\d]{1,5})?)\/.*/.test(url) ){
url = href.trim().match(/^(http:|https:)/im)[0]+"//"+url.replace(/^(\/{0,2})/img,"")
}else if( /^(\/)/.test(url)){
url = location.origin + url
}
}
if(! urls.includes(url.trim())){
urls.push(url.trim())
}else{
return;
}
switch( location.host )
{
case 'x.com':
if( /(\/pl\/mp4a\/|\/pl\/avc1)/i.test( url ) ){
return
}
break;
case 'www.iwara.tv':
if( !/(_Source\.mp4)/i.test( url ) ){
return
}
break;
default:
break;
}
GM_xhr( url)
function GM_xhr( url, i=0){
var head
var Headers
var Length
var Type
var origin= (new URL(href)).origin
var default_web = false
switch( location.host )
{
case 'web.telegram.org':
head = { Range:"bytes=0-200", "user-agent":
"User-Agent Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/117.0"}
default_web = true
break;
}
if (!default_web){
origin= (new URL(href)).origin
switch( i )
{
case 0:
head = {Range:"bytes=0-200", 'Cache-Control':"no-store"}
break;
case 1:
head = {Referer: href, Range:"bytes=0-200", "Cache-Control":"no-store"}
break;
case 2:
head = {Referer: href, Origin: origin ,Range:"bytes=0-200", "Cache-Control":"no-store"}
break;
case 3:
href = (new URL(url)).origin+'/'
head = {Referer: href, Origin: origin }
break;
case 4:
origin = location.origin
head = {Referer: href, Origin: location.origin ,Range:"bytes=0-200", "Cache-Control":"no-store"}
break;
case 5:
href = url
head = {Referer: url, Range:"bytes=0-200"}
break;
case 6:
default:
console.log("Url始终HEAD 错误，不添加列表: "+url)
return;
break;
}
}
var get = GM_xmlhttpRequest({
method: "GET",
url: url,
fetch: true,
headers: head,
onerror: function(x) {
console.log("Url错误onerror,HEAD出错 : "+url)
console.log(x)
url_info.push( { url:url, info: "Url错误onerror,HEAD出错 ", response: x} )
GM_xhr( url , i+1 )
get.abort()
return;
},
onload: function(response) {
console.log(get)
console.log(response)
console.log(url)
console.log(head)
var x
var num
if (/https:\/\/web\.telegram\.org\/a\/progressive\/document\d{19}$/i.test( url ) ){
Type = 'normal'; VorA = "MyVideo"
x = $("#MyUrls .MyNR>."+VorA)
num = x.find('.isUrl').length+1
x.find(".urlnone").remove()
x.append("<div class='isUrl' style='height: 31px;'>"+
"<hr    > " +
"<div   class='No-isUrl'> " +num+"、</div>"+
"<input disabled  data-type='"+Type+"'  class='downUrl"+num+"'   title='"+url+"' value='"+url+"'> "+
"<input title='"+ (name=="" ? '自定义保存文件名' : name) +"'        class='downName"+num+"'  placeholder='文件名' value='"+name+"'>"+
"<div   style='display: inline-block;'  class='But GoUrl"+num+"'      >访问</div>"+
"<div   style='display: inline-block;'  class='But CopyUrl"+num+"'    >复制</div>"+
"<div   style='display: inline-block;'  data-head_i='"+i+"' data-head_href='"+href+"' data-head_origin='"+origin+"' data-Headers='"+Headers+"' data-Length='"+Length+"' data-Type='"+Type+"' data-head='"+ JSON.stringify(head) +
"'  class='But noSaveUrl"+num+"'    >No</div>"+
"<div   style='display: none;        '  class='But StopSaveUrl"+num+"'> 0% </div>"+
"<div   style='display: inline-block;'  class='But playUrl"+num+"'    >播放</div>&nbsp"+
"<div   title='删除此条'                class='rmUrl"+num+"'          >&nbspx&nbsp</div>"+
"</div>")
functionAll(num,VorA)
if($("#MyUrls").css("display")=="none"){
$("#redPoint").css("display","block")
$(".MyBT #"+VorA).click()
if(GM_getValue("auto_n", 1) == set['auto_n']){
$("#MyUpDown").click()
}
}
return
}else{
if(response.status/100>=3&& response.status!=404 ){
console.log("Url错误onerror,HEAD 403: "+url)
url_info.push( { url:url, info: "Url错误onerror,HEAD 403", response: response} )
GM_xhr( url , i+1 )
return;
}
if(response.status/100>=3){
console.log("Url错误onerror,HEAD出错 不添加列表: "+url)
console.log(response)
url_info.push( { url:url, info: "Url错误status>400 不添加列表", response: response} )
get.abort()
}
Headers = response.responseHeaders;
if( Headers == undefined || Headers == null || Headers ==""||Headers.length<1 ){
console.log("Url:"+url+",HEAD出错: responseHeaders 为空")
url_info.push( { url:url, info: "HEAD出错: responseHeaders 为空", response: response} )
return;
}
Type = Headers.match(/content-type:\s*[\S]+\s/im)
var VorA = "MyVideo"
if(Type == undefined || Type == null || Type.length<1){
if( /^#EXTM3U/i.test(response.responseText) ){
Type = 'hls'; VorA = "MyVideo"
}else{
console.log("Type为空"+url)
console.log(response)
url_info.push( { url:url, info: "Type为空", response: response} )
return
}
}else{
Type = Type[0].replace('content-type:','').trim()
if( /.*video\/mp4.*/i.test( Type ) || ( /application\/octet-stream/i.test( Type ) && /mp4\??.*/i.test(url) ) ){
Length = Headers.match(/content-range:\s*bytes\s*0-[\d]+\/[\d]+\s/im)[0].replace(/.*\//img,'').trim()
if( Length < 1024*1024){
console.log("嗅探到的视频太小x1："+Length+"B ,丢弃："+url)
url_info.push( { url:url, info: "嗅探到的视频太小x1："+Length+"B ,丢弃", response: response} )
return;
}
Type = 'normal'; VorA = "MyVideo"
}else if( /.*\/.*mpegurl.*/i.test( Type )){
Type = 'hls'; VorA = "MyVideo"
}else if( /.*audio\/.*/i.test( Type )){
Type = 'auto'; VorA = "MyAudio"
}else if( /.*(text\/[\w]*).*/i.test( Type ) || /application\/octet-stream/i.test( Type ) ) {
if( /^#EXTM3U/i.test(response.responseText) ){
Type = 'hls'; VorA = "MyVideo"
}else{
url_info.push( { url:url, info: "Type:"+Type, response: response} )
console.log(Type, url)
return;
}
}else{
url_info.push( { url:url, info: "Type:"+Type, response: response} )
console.log(Type, url)
return;
}
}
}
x = $("#MyUrls .MyNR>."+VorA)
x.find(".urlnone").remove()
num = x.find('.isUrl').length+1
x.append("<div class='isUrl' style='height: 31px;'>"+
"<hr    > " +
"<div   class='No-isUrl'> " +num+"、</div>"+
"<input disabled  data-type='"+Type+"'  class='downUrl"+num+"'   title='"+url+"' value='"+url+"'> "+
"<input title='"+ (name=="" ? '自定义保存文件名' : name) +"'        class='downName"+num+"'  placeholder='文件名' value='"+name+"'>"+
"<div   style='display: inline-block;'  class='But GoUrl"+num+"'      >访问</div>"+
"<div   style='display: inline-block;'  class='But CopyUrl"+num+"'    >复制</div>"+
"<div   style='display: inline-block;'  data-head_i='"+i+"' data-head_href='"+href+"' data-head_origin='"+origin+"' data-Headers='"+Headers+"' data-Length='"+Length+"' data-Type='"+Type+"' data-head='"+ JSON.stringify(head) +
"'  class='But SaveUrl"+num+"'    >下载</div>"+
"<div   style='display: none;        '  class='But StopSaveUrl"+num+"'> 0% </div>"+
"<div   style='display: inline-block;'  class='But playUrl"+num+"'    >播放</div>&nbsp"+
"<div   title='删除此条'                class='rmUrl"+num+"'          >&nbspx&nbsp</div>"+
"</div>")
functionAll(num,VorA)
if($("#MyUrls").css("display")=="none"){
$("#redPoint").css("display","block")
$(".MyBT #"+VorA).click()
if(GM_getValue("auto_n", 1) == set['auto_n']){
$("#MyUpDown").click()
}
}
}
})
console.log(get)
}
}
function mkMenu(list){
list.forEach( function(menu){
var No = menu.No
var key = menu.key
var keyName = menu.keyName
var keyVal = GM_getValue( keyName, 1)
if( No == 0 ){
menuList[No] = GM_registerMenuCommand( key , function() { eval( keyName ) })
}else{
menuList[No] = GM_registerMenuCommand( key+info[ keyVal == set[keyName] ? 1 : 0 ], function() {
keyVal = keyVal == 1 ? 0 : 1
GM_setValue(keyName, keyVal);
mkMenu( [menu] )
}, { id: menuList[No] } );
}
})
}
function rmMenu(id){
GM_unregisterMenuCommand(id);
}
function dplayerUrl(url,i,type){
var lay_i = unsafeWindow.dpgiegei717index;
$('#layui-layer'+lay_i+',#layui-layer-shade'+lay_i).remove()
var index = layer.load(2);
var conf = {
type: 1,
title: i+"、"+url,
shadeClose: true,
offset: offset,
fixed: true,
maxmin: true,
resize: true,
airplay: true,
chromecast: true,
move: '.layui-layer-title',
moveOut: false,
btn: [],
area: weight,
content: "<div id='giegei717dplayer' style='width: 100%;height: 100%;display:flex;align-items:center;justify-content:center;'></div>"
,success:  function(layero, index){
unsafeWindow.dpgiegei717 = new DPlayer({
element: document.getElementById("giegei717dplayer"),
preload: 'auto',
hotkey: true,
volume: 1,
mutex: true,
loop: false,
airplay: true,
playbackSpeed: [0.1,0.5, 1, 1.25, 1.5, 2],
screenshot: true,
autoplay: true,
preventClickToggle: false,
contextmenu: [
{
text: '刷新视频',
click: (player) => {
player.switchVideo(
{
url: url,
type: type,
},
);
player.play()
},
},
{
text: '复制链接',
click: (player) => {
GM_setClipboard(url);
var aux = document.createElement("input");
aux.setAttribute("value", url);
document.body.appendChild(aux);
aux.select();
document.execCommand("copy");
document.body.removeChild(aux);
layer.msg("已复制")
}
}
],
video: {
url: url,
type: type,
},
});
dpgiegei717.video.crossOrigin=null;
dpgiegei717.on('error', function () {
if( firstVideo == 0 ){
console.log('加载video的扩展js')
GM_addElement('script', { src: 'https://cdn.bootcdn.net/ajax/libs/flv.js/1.6.2/flv.min.js',type: 'text/javascript' });
GM_addElement('script', { src: 'https://cdn.bootcdn.net/ajax/libs/shaka-player/4.3.5/shaka-player.compiled.min.js',type: 'text/javascript' });
GM_addElement('script', { src: 'https://cdn.bootcdn.net/ajax/libs/dashjs/4.6.0/dash.all.min.js',type: 'text/javascript' });
firstVideo = 1
dpgiegei717.switchVideo({ url: url, type: type, });
dpgiegei717.play()
}
});
var set = setInterval(function(){
if($('#giegei717dplayer>.dplayer-video-wrap>video').length>0){
var video = $('#giegei717dplayer>.dplayer-video-wrap> video[class^=dplayer-video]')[0]
stopSet()
var touchtime = 0;
var touchtarget;
$('#giegei717dplayer>.dplayer-video-wrap> video[class^=dplayer-video]').on("dblclick",function () {
if (document.fullscreenElement == null) {
dpgiegei717.fullScreen.request();
} else {
dpgiegei717.fullScreen.cancel();
}
}).on('touchstart', function(event) {
if (touchtime == 0) {
touchtime = new Date().getTime();
touchtarget = event.target;
} else {
if (event.target == touchtarget && new Date().getTime() - touchtime < 300) {
if (document.fullscreenElement == null) {
dpgiegei717.fullScreen.request();
} else {
dpgiegei717.fullScreen.cancel();
}
touchtime = 0;
} else {
touchtime = new Date().getTime();
touchtarget = event.target;
}
}
});
$('#giegei717dplayer .dplayer-icons-right').prepend('<div class="dplayer-icon dplayer-hzh-icon" data-balloon="画中画" data-balloon-pos="up">'+
'<span class="dplayer-icon-content"><svg width="24" height="22" xmlns="http://www.w3.org/2000/svg">'+
'<path  d="m19.22801,9.8748l-8.11544,0l0,5.89418l8.11544,0l0,-5.89418zm4.05772,7.85891l0,-13.77273c0,-1.0806 -0.91299,-1.94508 -2.02886,-1.94508l-18.25974,0c-1.11587,0 -2.02886,0.86448 -2.02886,1.94508l0,13.77273c0,1.0806 0.91299,1.96473 2.02886,1.96473l18.25974,0c1.11587,0 2.02886,-0.88413 2.02886,-1.96473zm-2.02886,0.01965l-18.25974,0l0,-13.80221l18.25974,0l0,13.80221z"></path>'+
'</svg></span>'+
'</div>')
$('#giegei717dplayer .dplayer-icons-right>.dplayer-hzh-icon').on('click',function(){
var that = this;
if(hzh == false && !document.pictureInPictureElement){
video.requestPictureInPicture();
video.addEventListener('enterpictureinpicture', function() {
hzh = true;
});
}else{
document.exitPictureInPicture();
video.addEventListener('leavepictureinpicture', function() {
hzh = false;
});
}
})
$('#giegei717dplayer .dplayer-icons-right').prepend('<div class="dplayer-icon dplayer-reload-icon" data-balloon="刷新" data-balloon-pos="up">'+
'<span class="dplayer-icon-content"><svg width="24" height="22" xmlns="http://www.w3.org/2000/svg" >'+
'<path stroke="null" d="m3.06995,10.0686a0.7572,0.7696 0 0 1 0.7572,0.7696l0,0.3848c0,4.45446 3.6853,8.08083 8.2482,8.08083c2.08458,0 4.04346,-0.7596 5.54802,-2.10102l0.19384,-0.17778l0.27638,-0.2632a0.7572,0.7696 0 0 1 1.06993,0.03694l0.51717,0.56181a0.7572,0.7696 0 0 1 -0.03559,1.08822l-0.27714,0.26243c-1.94677,1.85012 -4.53791,2.9014 -7.29185,2.9014c-3.33926,0 -6.32036,-1.53844 -8.24896,-3.94037l0,0.86195a0.7572,0.7696 0 0 1 -0.7572,0.7696l-0.7572,0a0.7572,0.7696 0 0 1 -0.7572,-0.7696l0,-7.69603a0.7572,0.7696 0 0 1 0.7572,-0.7696l0.7572,0zm8.54729,-9.23523c3.2802,0 6.2083,1.52381 8.11115,3.90342l0,-0.82501a0.7572,0.7696 0 0 1 0.7572,-0.7696l0.7572,0a0.7572,0.7696 0 0 1 0.7572,0.7696l0,7.69603a0.7572,0.7696 0 0 1 -0.7572,0.7696l-0.7572,0a0.7572,0.7696 0 0 1 -0.7572,-0.7696l0,-0.3848c0,-4.45831 -3.627,-8.08083 -8.11115,-8.08083c-2.15045,0 -4.16385,0.83579 -5.66614,2.29803l-0.17794,0.17855l-0.26502,0.27475a0.7572,0.7696 0 0 1 -1.07144,0.00924l-0.53988,-0.53872a0.7572,0.7696 0 0 1 -0.00909,-1.08899l0.26502,-0.27475a10.43424,10.60512 0 0 1 7.46449,-3.16691z"></path>'+
'</svg></span>'+
'</div>')
$('#giegei717dplayer .dplayer-icons-right>.dplayer-reload-icon').on('click',function(){
dpgiegei717.switchVideo({ url: url, type: type, });
dpgiegei717.play()
})
}
},50);
function stopSet(){
clearInterval(set);
}
window.onresize = function () {
w = window.innerWidth;
h = window.innerHeight;
$("#giegei717dplayer").parents("div[id^=layui-layer]").css({"max-width": w,"max-height": h } )
}
}
,end: function(){
$(".But:nth-last-of-type(2)").text('播放')
}
}
layer.close(index);
unsafeWindow.dpgiegei717index = layer.open(conf)
}
function me(){
var conf1 = {
formType: 0,
title: "支持作者,你的支持就是作者的动力！",
move: false,
shadeClose: true,
offset: '100px',
resize: false,
btn: ['点击关闭（点此关闭后以后不再自动弹出）'],
area: weight1,
content: "<h4 style='color:red'>注意：如果下载的视频不完整、缺少片段，可尝试在油猴扩展的脚本菜单中关闭视频下载去广告功能</h4><div id='giegei717dplayer' style='width: 500px;height: 500px;display:flex;align-items:center;justify-content:center;'><img src=''  border='0' width='100%' height='100%' /></div>"
,success: function(layero, index){
$('layui-layer-btn .layui-layer-btn0').css({'border-color': '#1e9fff !important','background-color': '#1e9fff !important','color': '#fff !important'})
}
,yes: function(index, layero){
GM_setValue("first1",99);
layer.close(index)
}
,cancel: function(index, layero, that){
GM_setValue("first1",99);
layer.close(index)
}
}
layer.open(conf1)
return
}
function FirstOpen(){
var one = GM_getValue("first1", 0)
if(one==0){
me();
};
}
})();