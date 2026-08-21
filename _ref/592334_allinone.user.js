// ==UserScript==
// @name         🔥 [2026] All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @namespace    https://tenzaa.com/
// @version      2026.08.19
// @description  [2026 Latest] Download ANY video from YouTube, TikTok, X(Twitter), Instagram, Facebook. Support 4K, MP3, MP4. No watermark, fast & free.
// @author       classccai
// @icon         https://tenzaa.com/favicon.ico
// @match        *://*.youtube.com/*
// @match        *://x.com/*
// @match        *://twitter.com/*
// @match        *://*.tiktok.com/*
// @match        *://*.instagram.com/*
// @match        *://*.facebook.com/*
// @match        https://tenzaa.com/*
// @grant        none
// @run-at       document-end
// @license      MIT

// @name:af      🔥 Alles-in-een Video Aflaaier | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:af Laai enige video van YouTube, TikTok, X(Twitter), Instagram, Facebook af.
// @name:am      🔥 ሁሉንም በአንድ ቪዲዮ አውራጅ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:am ማንኛውንም ቪዲዮ ከ YouTube, TikTok, X(Twitter), Instagram, Facebook ያውርዱ።
// @name:ar      🔥 محمل الفيديو الكل في واحد | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ar تنزيل أي فيديو من YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:as      🔥 অল-ইন-ৱান ভিডিঅ' ডাউনলোডাৰ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:as যিকোনো ভিডিঅ' ডাউনলোড কৰক YouTube, TikTok, X(Twitter), Instagram, Facebook পৰা।
// @name:az      🔥 Hər Şey Bir Arada Video Yükləyici | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:az YouTube, TikTok, X(Twitter), Instagram, Facebook-dan videoları endirin.
// @name:be      🔥 Універсальны загрузнік відэа | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:be Спампоўвайце відэа з YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:bg      🔥 Всичко-в-едно изтегляне на відэа | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:bg Изтеглете видео ад YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:bn      🔥 অল-ইন-ওয়ান ভিডিও ডাউনলোডার | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:bn YouTube, TikTok, X(Twitter), Instagram, Facebook থেকে ভিডিও ডাউনলোড করুন।
// @name:bs      🔥 Sve-u-jednom Video Preuzimač | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:bs Preuzmite videozapise sa YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ca      🔥 Descarregador de vídeos Tot en Un | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ca Baixeu vídeos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:cs      🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:cs Stáhněte si videa z YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:da      🔥 Alt-i-en Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:da Download videoer fra YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:de      🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:de Laden Sie Videos von YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:dz      🔥 ཆ་ཚང་བརྙན་འཁོར་ཕབ་ལེན། | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:dz YouTube, TikTok, X(Twitter), Instagram, Facebook ལས་བརྙན་འཁོར་ཕབ་ལེན།
// @name:el      🔥 Λήψη βίντεο Όλα σε Ένα | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:el Λήψη βίντεο από YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:en      🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:en Download videos from YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:en-GB   🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:en-GB Download videos from YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:en-US   🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:en-US Download videos from YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:eo      🔥 Ĉio-en-unu Video Elŝutilo | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:eo Elŝutu videojn de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:es      🔥 Descargador de vídeos Todo en Uno | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:es Descarga vídeos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:es-419  🔥 Descargador de vídeos Todo en Uno | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:es-419 Descarga vídeos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:es-US   🔥 Descargador de vídeos Todo en Uno | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:es-US Descarga vídeos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:et      🔥 Kõik-ühes video allalaadija | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:et Laadige alla videoid YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:eu      🔥 Dena-batean Bideo Deskargatzailea | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:eu Deskargatu bideoak YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:fa      🔥 دانلودر ویدیوی همه‌کاره | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:fa ویدیوها را از YouTube, TikTok, X(Twitter), Instagram, Facebook دانلود کنید.
// @name:fi      🔥 Kaikki yhdessä -videolataaja | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:fi Lataa videoita YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:fil     🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:fil I-download ang mga video mula sa YouTube, TikTok, X(Twitter), Instagram.
// @name:fr      🔥 Téléchargeur de vidéos Tout-en-un | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:fr Téléchargez des vidéos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:fr-CA   🔥 Téléchargeur de vidéos Tout-en-un | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:fr-CA Téléchargez des vidéos de YouTube, TikTok, X(Twitter), Instagram.
// @name:ga      🔥 Íoslódálaí Físeáin Uile-i-Aon | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ga Íoslódáil físeáin ó YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:gl      🔥 Descargador de vídeos Todo en Un | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:gl Descarga vídeos de YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:gn      🔥 Ta'anga Mýi Mboguejyha | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:gn Emboguejy ta'anga mýi YouTube, TikTok, X(Twitter), Instagram, Facebook-gui.
// @name:gu      🔥 ઑલ-ઇન-વન વિડિઓ ડાઉનલોડર | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:gu YouTube, TikTok, X(Twitter), Instagram, Facebook પરથી વિડિઓઝ ડાઉનલોડ કરો.
// @name:ha      🔥 All-in-One Bidiyo Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ha Zazzage bidiyon YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:hi      🔥 ऑल-इन-वन वीडियो डाउनलोडर | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:hi YouTube, TikTok, X(Twitter), Instagram, Facebook से वीडियो डाउनलोड करें।
// @name:hr      🔥 Sve-u-jednom Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:hr Preuzmite videozapise s YouTubea, TikToka, X(Twitter), Instagrama.
// @name:hu      🔥 Minden-az-egyben Videó Letöltő | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:hu Töltsön le videókat a YouTube, TikTok, X(Twitter), Instagram oldalakról.
// @name:hy      🔥 Համապարփակ Վիդեո Ներբեռնիչ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:hy Ներբեռնեք տեսանյութեր YouTube, TikTok, X(Twitter), Instagram, Facebook-ից:
// @name:id      🔥 Pengunduh Video All-in-One | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:id Unduh video dari YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ig      🔥 Ihe Nbudata Vidiyo Niile | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ig Budata vidiyo na YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:is      🔥 Allt-í-einu Myndbanda Niðurhalari | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:is Sæktu myndbönd frá YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:it      🔥 Scarica Video Tutto-in-Uno | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:it Scarica video da YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:iw      🔥 מוריד וידאו הכל באחד | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:iw הורד סרטונים מ-YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ja      🔥 オールインワン動画ダウンローダー | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ja YouTube、TikTok、X(Twitter)、Instagram、Facebookから動画をダウンロード。
// @name:jv      🔥 Pengunduh Video All-in-One | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:jv Unduh video saka YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ka      🔥 All-in-One ვიდეო ჩამომტვირთავი | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ka ჩამოტვირთეთ ვიდეოები YouTube, TikTok, X(Twitter), Instagram-დან.
// @name:kk      🔥 Универсалды Видео Жүктеуші | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:kk YouTube, TikTok, X(Twitter), Instagram, Facebook-тен бейнелерді жүктеңіз.
// @name:km      🔥 កម្មវិធីទាញយកវីដេអូទាំងអស់ក្នុងតែមួយ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:km ទាញយកវីដេអូពី YouTube, TikTok, X(Twitter), Instagram, Facebook។
// @name:kn      🔥 ಆಲ್-ಇನ್-ಒನ್ ವೀಡಿಯೊ ಡೌನ್‌ಲೋಡರ್ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:kn YouTube, TikTok, X(Twitter), Instagram, Facebook ನಿಂದ ವೀಡಿಯೊಗಳನ್ನು ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ.
// @name:ko      🔥 올인원 비디오 다운로더 | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ko YouTube, TikTok, X(Twitter), Instagram, Facebook에서 영상을 다운로드하세요.
// @name:ky      🔥 Бардыгы бир жерде Видео Жүктөөчү | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ky YouTube, TikTok, X(Twitter), Instagram, Facebook'тан видеолорду жүктөп алыңыз.
// @name:lo      🔥 ດາວໂຫລດວິດີໂອທັງໝົດក្នុងໜຶ່ງດຽວ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:lo ດາວໂຫລດວິດີໂອຈາກ YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:lt      🔥 Viskas viename vaizdo įrašų atsisiuntėjas | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:lt Atsisiųskite vaizdo įrašus iš YouTube, TikTok, X(Twitter), Instagram.
// @name:lv      🔥 Viss vienā Video Lejupielādētājs | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:lv Lejupielādējiet videoklipus no YouTube, TikTok, X(Twitter), Instagram.
// @name:mg      🔥 Mpisintona Video Feno | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:mg Sintomy ny video avy amin'ny YouTube, TikTok, X(Twitter), Instagram.
// @name:mk      🔥 Сè-во-едно Преземач на видео | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:mk Преземете видеа од YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ml      🔥 ഓൾ-ইন-വൺ വീഡിയോ ഡൗൺലോഡർ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ml YouTube, TikTok, X(Twitter), Instagram, Facebook എന്നിവയിൽ നിന്ന് വീഡിയോകൾ.
// @name:mn      🔥 Бүх видеог нэг дор татагч | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:mn YouTube, TikTok, X(Twitter), Instagram, Facebook-аас видео татах.
// @name:mr      🔥 ऑल-इन-वन व्हिडिओ डाउनलोडर | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:mr YouTube, TikTok, X(Twitter), Instagram, Facebook वरून व्हिडिओ डाउनलोड करा.
// @name:ms      🔥 Pemuat Turun Video Semua-dalam-Satu | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ms Muat turun video dari YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:mt      🔥 Tniżżil tal-Vidjo Kollox f'Wieħed | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:mt Niżżel vidjows minn YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:my      🔥 All-in-One ဗီဒီယို ডေါင်းလုဒ် | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:my YouTube, TikTok, X(Twitter), Instagram, Facebook တို့မှ ဗီဒီယိုများကို ဒေါင်းလုဒ်လုပ်ပါ။
// @name:ne      🔥 अल-ইন-वन भिडियो डाउनलोडर | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ne YouTube, TikTok, X(Twitter), Instagram, Facebook बाट भिडियोहरू डाउनलोड गर्नुहोस्।
// @name:nl      🔥 Alles-in-één Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:nl Download video's van YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:no      🔥 Alt-i-ett Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:no Last ned videoer fra YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:om      🔥 Buufataa Viidiyoo Hunda | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:om Viidiyoo YouTube, TikTok, X(Twitter), Instagram, Facebook irraa buufadhu.
// @name:or      🔥 ଅଲ୍-ଇନ୍-ୱାନ୍ ଭିଡିଓ ଡାଉନଲୋଡର୍ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:or YouTube, TikTok, X(Twitter), Instagram, Facebook ରୁ ଭିଡିଓ ଡାଉନଲୋଡ୍ କରନ୍ତু |
// @name:pa      🔥 ਆਲ-ਇਨ-ਵਨ ਵੀਡੀਓ ਡਾਊਨਲੋਡর | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:pa YouTube, TikTok, X(Twitter), Instagram, Facebook ਤੋਂ ਵੀਡੀਓ ਡਾਊਨਲੋড করো।
// @name:pl      🔥 Pobieracz Wideo Wszystko-w-Jednym | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:pl Pobieraj filmy z YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ps      🔥 د ټولو په یو کې ویډیو ډاونلوډر | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ps د YouTube, TikTok, X(Twitter), Instagram, Facebook څخه ویډیوګانې ډاونلوډ کړئ।
// @name:pt      🔥 Baixador de Vídeos Tudo-em-Um | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:pt Baixe vídeos do YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:pt-BR   🔥 Baixador de Vídeos Tudo-em-Um | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:pt-BR Baixe vídeos do YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:pt-PT   🔥 Transferidor de Vídeos Tudo-em-Um | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:pt-PT Transfira vídeos do YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ro      🔥 Descărcător Video Tot-în-Unul | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ro Descărcați videoclipuri de pe YouTube, TikTok, X(Twitter), Instagram.
// @name:ru      🔥 Универсальный загрузчик видео | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ru Скачивайте видео с YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:rw      🔥 All-in-One Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:rw Kuramo vidiyo kuri YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:si      🔥 සියල්ල-එකම වීඩියෝ බාගන්නා | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:si YouTube, TikTok, X(Twitter), Instagram, Facebook වෙතින් වීඩියෝ බාගන්න.
// @name:sk      🔥 Všetko-v-jednom Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sk Sťahujte videá z YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:sl      🔥 Prenosnik Videa Vse-v-enem | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sl Prenesite videoposnetke iz YouTube, TikTok, X(Twitter), Instagram.
// @name:sn      🔥 Yese-mu-Imwe Vhidhiyo Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sn Dhawunirodha mavhidhiyo kubva kuYouTube, TikTok, X(Twitter), Instagram.
// @name:so      🔥 Soo Degsade Video Dhammaan-Mid ah | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:so Soo degso fiidiyowyada YouTube, TikTok, X(Twitter), Instagram.
// @name:sq      🔥 Shkarkues Video Gjithçka-në-Një | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sq Shkarkoni video nga YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:sr      🔥 Sve-u-jednom Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sr Preuzmite video zapise sa YouTube, TikTok, X(Twitter), Instagram.
// @name:sr-Latn 🔥 Sve-u-jednom Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sr-Latn Preuzmite video zapise sa YouTube, TikTok, X(Twitter), Instagram.
// @name:st      🔥 Khoasolla Livideo Tsohle ho e 'Ngoe | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:st Khoasolla livideo ho tsoa ho YouTube, TikTok, X(Twitter), Instagram.
// @name:sv      🔥 Allt-i-ett Video Downloader | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sv Ladda ner videor från YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:sw      🔥 Kipakuzi cha Video Zote-kwa-Moja | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:sw Pakua video kutoka YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ta      🔥 ஆல்-ইন-ஒன் வீடியோ டவுன்லோடர் | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ta YouTube, TikTok, X(Twitter), Instagram, Facebook ஆகியவற்றிலிருந்து வீடியோக்கள்.
// @name:te      🔥 ఆల్-ইন-వన్ వీడియో డౌన్‌లోడర్ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:te YouTube, TikTok, X(Twitter), Instagram, Facebook నుండి వీడియోలు.
// @name:tg      🔥 Зеркашӣкунандаи Видеои Ҳамагир | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:tg Видеоҳоро аз YouTube, TikTok, X(Twitter), Instagram, Facebook зеркашӣ кунед.
// @name:th      🔥 ดาวน์โหลดวิดีโอแบบออลอินวัน | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:th ดาวน์โหลดวิดีโอจาก YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ti      🔥 ኩሉ ኣብ ሓደ ቪድዮ መውረዲ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ti ካብ YouTube, TikTok, X(Twitter), Instagram, Facebook ቪድዮታት ኣውርድ።
// @name:tk      🔥 Ählisi Bir ýerde Wideo Ýükleýji | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:tk YouTube, TikTok, X(Twitter), Instagram, Facebook-dan wideolary göçürip alyň.
// @name:tr      🔥 Hepsi Bir Arada Video İndirici | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:tr YouTube, TikTok, X(Twitter), Instagram, Facebook'tan videolar indirin.
// @name:ug      🔥 ھەممىباب سىن چۈشۈرگۈچ | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ug YouTube, TikTok, X(Twitter), Instagram, Facebook تىن سىن چۈشۈرۈڭ।
// @name:uk      🔥 Універсальний завантажувач відео | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:uk Завантажуйте відео з YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:ur      🔥 آل ان ون ویڈیو ڈاؤنلوڈر | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:ur YouTube, TikTok, X(Twitter), Instagram, Facebook سے ویڈیوز ڈاؤن لوڈ کریں۔
// @name:uz      🔥 Hammasi bitta Video Yuklab Oluvchi | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:uz YouTube, TikTok, X(Twitter), Instagram, Facebook-dan videolarni yuklab oling.
// @name:vi      🔥 Trình tải xuống video Tất cả trong Một | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:vi Tải xuống video từ YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:yo      🔥 Ohun Nkan Gbigbasilẹ Fidio Gbogbo-ni-Ẹkan | YouTube, TikTok, X(Twitter), Instagram
// @description:yo Gba awọn fidio lati YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @name:zh-CN   🔥 多合一视频下载器【2026最新】- 支持 YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:zh-CN 【2026最新】一键下载 YouTube, TikTok, X(Twitter), Instagram, Facebook 视频。支持 4K, 1080P, MP3, MP4，无水印，极速稳定。
// @name:zh-HK   🔥 多合一影片下載器【2026最新】- 支援 YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:zh-HK 【2026最新】一鍵下載 YouTube, TikTok, X(Twitter), Instagram, Facebook 影片。支援 4K, 1080P, MP3, MP4，無浮水印，極速穩定。
// @name:zh-TW   🔥 多合一影片下載器【2026最新】- 支援 YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:zh-TW 【2026最新】一鍵下載 YouTube, TikTok, X(Twitter), Instagram, Facebook 影片。支援 4K, 1080P, MP3, MP4，無浮水印，極速穩定。
// @name:zu      🔥 Konke-ku-Kunye Isilandi se-Video | YouTube, TikTok, X(Twitter), Instagram, Facebook
// @description:zu Landa amavidiyo kusuka ku-YouTube, TikTok, X(Twitter), Instagram, Facebook.
// @match        *://youtube.com/*
// @match        *://youtu.be/*
// @match        *://tiktok.com/*
// @match        *://instagram.com/*
// @match        *://facebook.com/*
// @match        *://tenzaa.com/*
// @downloadURL https://update.greasyfork.org/scripts/592334/%F0%9F%94%A5%20%5B2026%5D%20All-in-One%20Video%20Downloader%20%7C%20YouTube%2C%20TikTok%2C%20X%28Twitter%29%2C%20Instagram%2C%20Facebook.user.js
// @updateURL https://update.greasyfork.org/scripts/592334/%F0%9F%94%A5%20%5B2026%5D%20All-in-One%20Video%20Downloader%20%7C%20YouTube%2C%20TikTok%2C%20X%28Twitter%29%2C%20Instagram%2C%20Facebook.meta.js
// ==/UserScript==

(function () {
    "use strict";

    // ============================================================
    // TENZAA All-in-One Video Downloader - userscript body
    // Shows a floating download button over videos on YouTube,
    // TikTok, X/Twitter, Instagram and Facebook, and opens the
    // tenzaa.com navigation page, where the user picks a clean
    // (ad-free / captcha-free) downloader site.
    // ============================================================

    var SITE = "https://tenzaa.com";

    var host = window.location.hostname;
    var platform = "youtube";

    if (/x\.com|twitter\.com/i.test(host)) platform = "twitter";
    if (/tiktok\.com/i.test(host)) platform = "tiktok";
    if (/instagram\.com/i.test(host)) platform = "instagram";
    if (/facebook\.com/i.test(host)) platform = "facebook";

    // Nothing to do on the downloader site itself.
    if (/tenzaa\.com/i.test(host)) return;

    var style = document.createElement("style");
    style.textContent = [
        "#tenzaa-aio-btn {",
        "  position: fixed;",
        "  z-index: 2147483647 !important;",
        "  background: rgba(20,20,20,0.85);",
        "  backdrop-filter: blur(8px);",
        "  -webkit-backdrop-filter: blur(8px);",
        "  color: #fff;",
        "  border: 1px solid rgba(255,255,255,0.2);",
        "  width: 40px; height: 40px;",
        "  border-radius: 50%;",
        "  display: none;",
        "  align-items: center; justify-content: center;",
        "  cursor: pointer;",
        "  transition: background .2s, transform .15s;",
        "  pointer-events: none !important;",
        "  box-shadow: 0 4px 12px rgba(0,0,0,.5);",
        "  font-size: 16px;",
        "}",
        "#tenzaa-aio-btn.tenzaa-hover {",
        "  background: rgba(220,38,38,1);",
        "  transform: scale(1.1);",
        "  border-color: rgba(255,255,255,0.4);",
        "}",
        "#tenzaa-aio-btn svg { fill: currentColor; width: 20px; height: 20px; pointer-events: none; }",
        "#tenzaa-tip {",
        "  position: fixed; z-index: 2147483646 !important;",
        "  background: rgba(15,23,42,0.92); color: #fff;",
        "  padding: 6px 10px; border-radius: 8px;",
        "  font: 12px/1.4 system-ui, sans-serif;",
        "  pointer-events: none; display: none;",
        "  white-space: nowrap;",
        "}"
    ].join("\n");
    document.head.appendChild(style);

    var btn = document.createElement("div");
    btn.id = "tenzaa-aio-btn";
    var svgNs = "http://www.w3.org/2000/svg";
    var svgIcon = document.createElementNS(svgNs, "svg");
    svgIcon.setAttribute("viewBox", "0 0 24 24");
    var path = document.createElementNS(svgNs, "path");
    path.setAttribute("d", "M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z");
    svgIcon.appendChild(path);
    btn.appendChild(svgIcon);
    document.body.appendChild(btn);

    var tip = document.createElement("div");
    tip.id = "tenzaa-tip";
    tip.textContent = "TENZAA Download";
    document.body.appendChild(tip);

    var activeVideo = null;
    var mouseX = -100;
    var mouseY = -100;

    // ---------- URL normalization ----------
    function cleanUrl(raw) {
        if (!raw) return raw;
        try {
            var u = new URL(raw);
            ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "si", "spm", "share_source", "is_from_webapp", "is_copy_link", "sender_device", "web_id", "igshid"].forEach(function (k) { u.searchParams.delete(k); });
            if (platform === "youtube") {
                if (u.hostname === "youtu.be") return "https://www.youtube.com/watch?v=" + u.pathname.split("/").filter(Boolean)[0];
                if (u.pathname.startsWith("/shorts/")) {
                    var sid = u.pathname.split("/")[2];
                    if (sid) return "https://www.youtube.com/shorts/" + sid;
                }
                var v = u.searchParams.get("v");
                if (v) return "https://www.youtube.com/watch?v=" + v;
            }
            if (platform === "tiktok") {
                var m = u.pathname.match(/^\/(@[^/]+)\/video\/(\d+)/);
                if (m) return "https://www.tiktok.com/" + m[1] + "/video/" + m[2];
            }
            if (platform === "twitter") {
                var sm = u.pathname.match(/^\/([^/]+)\/status\/(\d+)/);
                if (sm) return "https://x.com/" + sm[1] + "/status/" + sm[2];
            }
            if (platform === "instagram") {
                var im = u.pathname.match(/^\/(?:p|reel|reels|tv)\/([^/]+)/);
                if (im) return "https://www.instagram.com/" + u.pathname.split("/")[1] + "/" + im[1] + "/";
            }
            if (platform === "facebook") {
                var fv = u.searchParams.get("v");
                if (fv) return "https://www.facebook.com/watch/?v=" + fv;
                var fm = u.pathname.match(/^\/(?:videos|reel|reels|watch)\/(\d+)/);
                if (fm) return "https://www.facebook.com/watch/?v=" + fm[1];
            }
            return u.origin + u.pathname;
        } catch (e) {
            return raw;
        }
    }

    // ---------- Resolve the video URL under the cursor ----------
    function getTargetUrl(videoElement) {
        if (platform === "youtube") {
            var container = videoElement.closest("ytd-rich-item-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytd-reel-item-renderer, ytd-grid-video-renderer, ytd-compact-video-renderer, ytd-video-renderer, ytd-thumbnail");
            if (container) {
                var link = container.querySelector('a[href*="/shorts/"], a[href*="/watch?v="]');
                if (link && link.href) return cleanUrl(link.href);
            }
            var ytPlayer = document.querySelector("#movie_player");
            if (ytPlayer && ytPlayer.getVideoData) {
                var data = ytPlayer.getVideoData();
                if (data && data.video_id) return "https://www.youtube.com/watch?v=" + data.video_id;
            }
            if (location.pathname.startsWith("/shorts/")) {
                var id = location.pathname.split("/")[2];
                if (id) return "https://www.youtube.com/shorts/" + id;
            }
            var params = new URLSearchParams(location.search);
            var v = params.get("v");
            if (v) return "https://www.youtube.com/watch?v=" + v;
            return cleanUrl(location.href);
        }
        if (platform === "twitter") {
            var article = videoElement.closest("article");
            if (article) {
                var links = Array.from(article.querySelectorAll('a[href*="/status/"]'));
                var statusLink = links.find(function (l) { return /\/status\/\d+$/.test(l.href); });
                if (statusLink) return cleanUrl(statusLink.href);
                if (links.length) return cleanUrl(links[0].href);
            }
            return cleanUrl(location.href);
        }
        if (platform === "tiktok") {
            var cur = videoElement.parentElement;
            var videoId = "";
            var username = "";
            var depth = 0;
            while (cur && depth < 25) {
                if (!videoId && cur.id && cur.id.indexOf("xgwrapper") >= 0) {
                    var idMatch = cur.id.match(/\d{15,}/);
                    if (idMatch) videoId = idMatch[0];
                }
                if (!username) {
                    var userLink = cur.querySelector('a[href*="/@"]');
                    if (userLink) {
                        var userMatch = userLink.href.match(/\/(@[^/?]+)/);
                        if (userMatch) username = userMatch[1];
                    }
                }
                if (videoId && username) break;
                cur = cur.parentElement;
                depth++;
            }
            if (videoId && username) return "https://www.tiktok.com/" + username + "/video/" + videoId;
            if (videoId) return "https://www.tiktok.com/video/" + videoId;
            return cleanUrl(location.href);
        }
        if (platform === "instagram") {
            var igArticle = videoElement.closest("article");
            if (igArticle) {
                var a = igArticle.querySelector('a[href*="/p/"], a[href*="/reels/"], a[href*="/reel/"]');
                if (a) return cleanUrl(location.origin + a.getAttribute("href"));
            }
            return cleanUrl(location.href);
        }
        if (platform === "facebook") {
            var fm = location.href.match(/(?:v=|watch\/|reel\/|reels\/|videos\/)(\d+)/);
            if (fm) return "https://www.facebook.com/watch/?v=" + fm[1];
            var fbArticle = videoElement.closest('[role="article"], [data-pagelet]');
            if (fbArticle) {
                var flinks = Array.from(fbArticle.querySelectorAll('a[href*="/videos/"], a[href*="/watch/"], a[href*="/reel/"]'));
                if (flinks.length) return cleanUrl(flinks[0].href);
            }
            return cleanUrl(location.href);
        }
        return cleanUrl(location.href);
    }

    function updateBtnPos() {
        if (!activeVideo || btn.style.display === "none") return;
        var rect = activeVideo.getBoundingClientRect();
        var btnSize = 40;
        var top = rect.top + 15;
        var left = rect.left + (rect.width / 2) - (btnSize / 2);
        if (left < 5) left = 5;
        if (top < 5) top = 5;
        if (top > window.innerHeight - 45) top = window.innerHeight - 45;
        if (left > window.innerWidth - btnSize - 5) left = window.innerWidth - btnSize - 5;
        btn.style.top = top + "px";
        btn.style.left = left + "px";
        tip.style.top = (top + btnSize + 4) + "px";
        tip.style.left = Math.max(4, left - 20) + "px";
    }

    function checkHover() {
        if (mouseX < 0 || mouseY < 0) return;
        var btnRect = btn.getBoundingClientRect();
        var isHoveringBtn = (btn.style.display === "flex" &&
            mouseX >= btnRect.left && mouseX <= btnRect.right &&
            mouseY >= btnRect.top && mouseY <= btnRect.bottom);
        if (isHoveringBtn) {
            btn.classList.add("tenzaa-hover");
            tip.style.display = "block";
            return;
        }
        btn.classList.remove("tenzaa-hover");
        tip.style.display = "none";

        var found = null;
        var videos = document.querySelectorAll("video");
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var rect = v.getBoundingClientRect();
            if (mouseX >= rect.left && mouseX <= rect.right &&
                mouseY >= rect.top && mouseY <= rect.bottom &&
                rect.width > 50 && rect.height > 50) {
                var st = window.getComputedStyle(v);
                if (st.display !== "none" && st.visibility !== "hidden") {
                    found = v;
                    break;
                }
            }
        }

        if (!found && platform === "youtube") {
            var wrappers = document.querySelectorAll("ytd-thumbnail, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytd-reel-item-renderer, ytd-rich-item-renderer, ytd-grid-video-renderer");
            for (var j = 0; j < wrappers.length; j++) {
                var w = wrappers[j];
                var wr = w.getBoundingClientRect();
                if (mouseX >= wr.left && mouseX <= wr.right && mouseY >= wr.top && mouseY <= wr.bottom) {
                    found = w;
                    break;
                }
            }
        }

        if (found) {
            var target = getTargetUrl(found);
            if (!target || target.indexOf("undefined") >= 0) {
                btn.style.display = "none";
                activeVideo = null;
                return;
            }
            activeVideo = found;
            btn.style.display = "flex";
            updateBtnPos();
        } else {
            btn.style.display = "none";
            activeVideo = null;
        }
    }

    window.addEventListener("mousemove", function (e) {
        mouseX = e.clientX;
        mouseY = e.clientY;
        checkHover();
    }, true);

    window.addEventListener("mousedown", function (e) {
        if (btn.style.display === "flex") {
            var rect = btn.getBoundingClientRect();
            if (e.clientX >= rect.left && e.clientX <= rect.right &&
                e.clientY >= rect.top && e.clientY <= rect.bottom) {
                e.preventDefault();
                e.stopPropagation();
                triggerDownload();
            }
        }
    }, true);

    window.addEventListener("scroll", function () {
        if (activeVideo && btn.style.display === "flex") updateBtnPos();
    }, { passive: true, capture: true });

    setInterval(function () {
        if (mouseX >= 0 && mouseY >= 0) checkHover();
    }, 300);

    function triggerDownload() {
        if (!activeVideo) return;
        var targetUrl = getTargetUrl(activeVideo);
        if (!targetUrl || targetUrl.indexOf("undefined") >= 0) return;

        var finalUrl = SITE + "/?platform=" + platform + "&url=" + encodeURIComponent(targetUrl);

        btn.textContent = "…";
        btn.style.transform = "scale(0.95)";
        tip.style.display = "none";

        window.open(finalUrl, "_blank");

        setTimeout(function () {
            btn.textContent = "";
            btn.appendChild(svgIcon);
            btn.style.transform = "scale(1)";
        }, 800);
    }
})();
