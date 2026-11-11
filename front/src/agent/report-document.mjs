import {themeReportHtml} from './report-theme.mjs';

// One document is used for the inline preview, separate reader and downloaded file.
export function reportDocumentHtml(html, siteOrigin) {
  const origin=new URL(siteOrigin).origin;
  if(!/^https?:\/\//.test(origin))throw new TypeError('Report links require a website origin');
  const security=`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><base href="${origin}/">`;
  let document=themeReportHtml(html).replace(/^\s*<!doctype\s+html[^>]*>/i,'');
  if(/<head(?:\s[^>]*)?>/i.test(document))document=document.replace(/<head(?:\s[^>]*)?>/i,head=>head+security);
  else if(/<html(?:\s[^>]*)?>/i.test(document))document=document.replace(/<html(?:\s[^>]*)?>/i,root=>root+'<head>'+security+'</head>');
  else document='<html><head>'+security+'</head><body>'+document+'</body></html>';
  return '<!doctype html>'+document;
}
