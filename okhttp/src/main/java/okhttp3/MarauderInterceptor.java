package okhttp3;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
// imports necessary for parsing the parents' bodies
import okio.Buffer;
import okio.BufferedSource;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

// refer https://stackoverflow.com/questions/35773028/how-to-change-body-in-okhttp-response
public final class MarauderInterceptor implements Interceptor {
    private final Marauder marauderObject;
    public MarauderInterceptor() {
      this.marauderObject = new Marauder();
    }
    public MarauderInterceptor(Marauder m) {
      this.marauderObject = m;
    }


    private boolean isPotentialMarauderParent(Response response) { // returns true if the input response is potentially a text file that needs to be parsed
      String responseContentType = response.header("content-type", "unknown").toLowerCase();
      List<String> listOfKnownTextualParentsContentTypes = Collections.synchronizedList(new ArrayList<String>());

      listOfKnownTextualParentsContentTypes.add("json");
      listOfKnownTextualParentsContentTypes.add("text");
      listOfKnownTextualParentsContentTypes.add("xml");
      listOfKnownTextualParentsContentTypes.add("application");
      listOfKnownTextualParentsContentTypes.add("charset");
      listOfKnownTextualParentsContentTypes.add("css");
      listOfKnownTextualParentsContentTypes.add("js");
      listOfKnownTextualParentsContentTypes.add("javascript");

      // this should be o (constant) as wwe have 8-10 strings
      // i didn't use hashset here as i want fuzzy match
      System.out.format("MARAUDER: when trying to find textual assets to parse, response content type is %s\n", responseContentType);
      for(String s : listOfKnownTextualParentsContentTypes) {
        if (responseContentType.contains(s)) return true;
      }
      return false;
    }

    @Override
    public okhttp3.Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        System.out.format("MARAUDER: in intercept. got chain request. \n");
        okhttp3.Response response = chain.proceed(request);
        System.out.format("MARAUDER: in intercept. got chain proceed with response code %d. \n", response.code());
        if(response.code() == 200) {
            System.out.format("MARAUDER: in intercept. got proceed. \n");
            if (isPotentialMarauderParent(response)) {
              System.out.format("MARAUDER: found a potential parent in URL %s\n", response.request().url().toString());
              try {
                // response.body() is a buffer that can be read once only
                // so we should not read it here. if we do, then the higher level app will not find anything
                // so instead, we use this as per https://stackoverflow.com/questions/27922703/accessing-body-string-of-an-okhttp-response-twice-results-in-illegalstateexcepti
                ResponseBody responseBody = response.body();
                BufferedSource source = responseBody.source();
                source.request(Long.MAX_VALUE); // request the entire body.
                Buffer buffer = source.buffer();
                // clone buffer before reading from it
                String responseBodyString = buffer.clone().readString(Charset.forName("UTF-8"));
                String contentEncoding = response.headers().get("Content-Encoding");
                System.out.format("MARAUDER: content encoding is %s\n", contentEncoding);
                this.marauderObject.addParentToSet(response.request.url().toString(), responseBodyString, response.request());
              } catch(java.io.IOException excep) {
                System.out.format("MARAUDER: caught exception when trying to process the response bodies to do parent-child optimization. so ignoring this parent. %s, %s\n", response.request().url().toString(), excep.toString());
              } catch(java.lang.NullPointerException excep) {
                System.out.format("MARAUDER: caught exception when trying to read response body %s, %s\n.", response.request().url().toString(), excep.toString());
              }
            }
        } else {
          System.out.format("response code is %d\n", response.code());
        }
        return response;
    }
}