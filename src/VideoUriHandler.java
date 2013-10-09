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


import com.rhomobile.rhodes.Logger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

public class VideoUriHandler implements UriHandler {

	private static final String TAG = "VideoUriHandler";
	
	private Context ctx;
	
	public VideoUriHandler(Context c) {
		ctx = c;
	}

	public boolean handle(String url) {
		String ext = MimeTypeMap.getFileExtensionFromUrl(url);
		if (ext == null || ext.length() == 0)
			return false;
		
		MimeTypeMap mt = MimeTypeMap.getSingleton();
		String mimeType = mt.getMimeTypeFromExtension(ext);
		if (mimeType == null)
			return false;
		
		if (!mimeType.startsWith("video/"))
			return false;
		
		Logger.D(TAG, "This is video url, handle it");
		
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(Uri.parse(url), mimeType);
		ctx.startActivity(Intent.createChooser(intent, "Choose video player"));
		return true;
	}

}
