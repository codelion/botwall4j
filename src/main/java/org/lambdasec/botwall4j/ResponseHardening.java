
package org.lambdasec.botwall4j;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 *
 */
public class ResponseHardening implements Filter {

 protected FilterConfig config;
    
 @Override
  public void init(FilterConfig fc) throws ServletException {
    this.config = fc;   
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain fc) 
          throws IOException, ServletException {
    SecretKey key;
    Map<String,String> keyStore;
    Map<String,IvParameterSpec> encryptedStore;
    
    SecureRandom random = new SecureRandom();
    byte iv[] = new byte[16];//generate random 16 byte IV AES is always 16bytes
    random.nextBytes(iv);
    IvParameterSpec ivspec = new IvParameterSpec(iv);
    
    try {
      if (request instanceof HttpServletRequest) {
        HttpSession st = ((HttpServletRequest) request).getSession();
        key = (SecretKey) st.getAttribute("key");
        keyStore = (Map<String, String>) st.getAttribute("keyStore");
        encryptedStore = (Map<String, IvParameterSpec>) st.getAttribute("encryptedStore");
        if(key == null) {
          try {
            key = KeyGenerator.getInstance("AES").generateKey();
            } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(ResponseHardening.class.getName()).log(Level.SEVERE, null, ex);
          }
          keyStore = new HashMap<>();
          encryptedStore = new HashMap<>();  
        }
        ServletRequest newRequest = new CharRequestWrapper((HttpServletRequest) request, key, keyStore, encryptedStore);
        ServletResponse newResponse = new CharResponseWrapper((HttpServletResponse) response);

        fc.doFilter(newRequest, newResponse);

        if (newResponse instanceof CharResponseWrapper) {
          String html = newResponse.toString();

          if (html != null) {
            Document doc = Jsoup.parseBodyFragment(html);
            harden(doc, "input[name]", "name", ivspec, keyStore, encryptedStore, key);
            harden(doc, "input[id]", "id", ivspec, keyStore, encryptedStore, key);
            harden(doc, "form[id]", "id", ivspec, keyStore, encryptedStore, key);
            response.getWriter().write(doc.html());
          }
        }
        st.setAttribute("key", key);
        st.setAttribute("keyStore", keyStore);
        st.setAttribute("encryptedStore", encryptedStore);
      }
    }
    catch (ServletException se) {
      if(response instanceof HttpServletResponse) {
        String str = "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n" +
                    "<html><head>\n" +
                    "<title>403 Forbidden</title>\n" +
                    "</head><body>\n" +
                    "<h1>Forbidden</h1>\n" +
                    "<hr>\n" +
                    "<address>Blocked by botwall4j</address>\n" +
                    "</body></html>";
        response.getWriter().write(str);
        ((HttpServletResponse) response).setStatus(403);
      }
      else Logger.getLogger(ResponseHardening.class.getName()).log(Level.SEVERE, null, se);
    }
  }
  
  @Override
  public void destroy() {
    // do clean up here
  }

  private void harden(Document doc, String selector, String attribute, IvParameterSpec ivspec, Map<String, String> keyStore,
          Map<String, IvParameterSpec> encryptedStore, SecretKey key) {
    Elements names = doc.select(selector);
    for (Element ele : names) {
      String name = ele.attr(attribute);
      if(encryptedStore.containsKey(name)) {
        String origName = Util.decrypt(name, encryptedStore.get(name), key);
        encryptedStore.remove(name);
        name = origName;
      }
      if(keyStore.containsKey(name)) {
        String origName = keyStore.get(name);
        keyStore.remove(name);
        name = origName;
      }
      String s;
      if(null != config.getInitParameter("hardeningType")) 
        switch (config.getInitParameter("hardeningType")) {
        case "random":
          s = UUID.randomUUID().toString();
          ele.attr(attribute,s);
          keyStore.put(s,name);
          break;
        case "encryption":
          s = Util.encrypt(name, ivspec, key);
          ele.attr(attribute,s);
          encryptedStore.put(s,ivspec);
          break;
      }
    }
  }

}
