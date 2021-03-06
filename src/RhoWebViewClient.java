/*------------------------------------------------------------------------
* (The MIT License)
* 
* Copyright (c) 2008-2011 Rhomobile, Inc.
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
* 
* http://rhomobile.com
*------------------------------------------------------------------------*/



import java.io.File;

import com.rhomobile.rhodes.LocalFileProvider;
import com.rhomobile.rhodes.Logger;
import com.rhomobile.rhodes.RhoConf;
import com.rhomobile.rhodes.RhodesActivity;
import com.rhomobile.rhodes.RhodesService;
import com.rhomobile.rhodes.extmanager.IRhoExtension;
import com.rhomobile.rhodes.extmanager.RhoExtManager;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Window;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class RhoWebViewClient extends WebViewClient
{
    private final String TAG = RhoWebViewClient.class.getSimpleName();
    private GoogleWebView mWebView;

    public RhoWebViewClient(GoogleWebView webView) {
        mWebView = webView;
    }

//    @Override
//    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//        Logger.I(TAG, "Resource request: " + url);
//        return null;
//    }
//
//    public void onLoadResource(WebView view, String url) {
//        Logger.I(TAG, "Load resource" + url);
//    }

    
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Logger.I(TAG, "Loading URL: " + url);
        boolean res = RhodesService.getInstance().handleUrlLoading(url);
        if (!res) {
            RhoExtManager.getImplementationInstance().onBeforeNavigate(view, url);
            
            if (URLUtil.isFileUrl(url)) {
                String path = Uri.parse(url).getPath();
                if(path.startsWith("/data/data")) {
                    Logger.T(TAG, "Local URL, override using LocalFileProvider");
                    String contentUrl = LocalFileProvider.uriFromLocalFile(new File(path)).toString();
                    Logger.T(TAG, "Overrided URL: " + contentUrl);
                    view.loadUrl(contentUrl);
                    return true;
                }
            }
            
        }
        return res;
    }
    
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        
        Logger.profStart("BROWSER_PAGE");
        
        RhoExtManager.getImplementationInstance().onNavigateStarted(view, url);

        if (mWebView.getConfig() != null && mWebView.getConfig().getBool("enablePageLoadingIndication"))
            RhodesActivity.safeGetInstance().getWindow().setFeatureInt(Window.FEATURE_PROGRESS, 0);
    }
    
    @Override
    public void onPageFinished(WebView view, String url) {

        Logger.profStop("BROWSER_PAGE");

        // Set title
        String title = view.getTitle();
        RhodesActivity.safeGetInstance().setTitle(title);
        if (mWebView.getConfig() != null && mWebView.getConfig().getBool("enablePageLoadingIndication"))
            RhodesActivity.safeGetInstance().getWindow().setFeatureInt(Window.FEATURE_PROGRESS, RhodesActivity.MAX_PROGRESS);

        RhoExtManager.getImplementationInstance().onNavigateComplete(view, url);
        
        super.onPageFinished(view, url);
    }
    
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        
        Logger.profStop("BROWSER_PAGE");
        
        StringBuilder msg = new StringBuilder(failingUrl != null ? failingUrl : "null");
        msg.append(" failed: ");
        msg.append(errorCode);
        msg.append(" - " + description);
        Logger.E(TAG, msg.toString());

        RhoExtManager.getImplementationInstance().onLoadError(view, IRhoExtension.LoadErrorReason.INTERNAL_ERROR);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        
        if(RhoConf.getBool("no_ssl_verify_peer")) {
            Logger.D(TAG, "Skip SSL error.");
            handler.proceed();
        } else {
            StringBuilder msg = new StringBuilder();
            msg.append("SSL error - ");
            switch(error.getPrimaryError()) {
            case SslError.SSL_NOTYETVALID:
                msg.append("The certificate is not yet valid: ");
                break;
            case SslError.SSL_EXPIRED:
                msg.append("The certificate has expired: ");
                break;
            case SslError.SSL_IDMISMATCH:
                msg.append("Hostname mismatch: ");
                break;
            case SslError.SSL_UNTRUSTED:
                msg.append("The certificate authority is not trusted: ");
                break;
            }
            msg.append(error.getCertificate().toString());
            Logger.W(TAG, msg.toString());
            handler.cancel();
        }
    }
}
