package webserver;

import db.MemoryUserRepository;
import http.util.*;
import model.User;
import db.Repository;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


public class RequestHandler implements Runnable{
    Socket connection;
    private static final Logger log = Logger.getLogger(RequestHandler.class.getName());
    private static final String ROOT_URL = "./webapp";
    private static final String HOME_URL = "/index.html";
    private static final String LOGIN_FAILED_URL = "/user/login_failed.html";
    private static final String LOGIN_URL = "/user/login.html";
    private static final String LIST_URL = "/user/list.html";
    private final Path homePath = Paths.get(ROOT_URL +HOME_URL);
    private final Repository repository;

    public RequestHandler(Socket connection) {
        this.connection = connection;
        this.repository = MemoryUserRepository.getInstance();
    }

    @Override
    public void run() {
        log.log(Level.INFO, "New Client Connect! Connected IP : " + connection.getInetAddress() + ", Port : " + connection.getPort());
        try (InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()){
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            DataOutputStream dos = new DataOutputStream(out);

            byte[] body =  new byte[0];

            String[] startLine = br.readLine().split(" ");
            String method = startLine[0];
            //method : GET
            String url = startLine[1];
            //url : /index.html

            int requestContentLength = 0;
            String cookie = "";

            while (true){
                final String line = br.readLine();
                if(line.equals("")){
                    break;
                }
                if (line.startsWith("Content-Length")){
                    requestContentLength = Integer.parseInt(line.split(": ")[1]);
                    // Header의 Content-Length 값이 필요
                }
                if (line.startsWith("Cookie")){
                    cookie = line.split(": ")[1];
                }
            }
            // 요구 사항 1번
            //http://localhost:{port}/
            if (method.equals("GET") && url.equals("/")) {
                body = Files.readAllBytes(homePath);
            }
            //http://localhost:{port}/index.html
            if (method.equals("GET") && url.endsWith(".html")) {
                body = Files.readAllBytes(Paths.get(ROOT_URL +url));
                //Files.readAllBytes(Paths.get(url)) 을 통해 쉽게 파일 내용을 읽어 byte로 반환할 수 있다.
            }

            //요구사항 2번, 3번, 4번
            if (url.equals("/user/signup")){
                String queryString = IOUtils.readData(br, requestContentLength);
                //readBody 메소드에 contentLength와 BufferedReader를 함께 보내어 body 값을 읽을 수 있을 것이다.
                Map<String, String> queryParameter = HttpRequestUtils.parseQueryParameter(queryString);
                User user = new User(queryParameter.get("userId"), queryParameter.get("password"), queryParameter.get("name"), queryParameter.get("email"));
                repository.addUser(user);
                //새로운 User Instance를 생성하고, 이를 MemoryUserRepository 저장
                response302Header(dos,HOME_URL);
                // status code 302 redirect에 대해 알아보고 적용
                return;
            }
            // 요구 사항 5번
            if (url.equals("/user/login")) {
                String queryString = IOUtils.readData(br, requestContentLength);
                Map<String, String> queryParameter = HttpRequestUtils.parseQueryParameter(queryString);
                User user = repository.findUserById(queryParameter.get("userId"));
                login(dos, queryParameter, user);
                return;
            }
            // 요구 사항 6번
            if (url.equals("/user/userList")) {
                if (!cookie.equals("logined=true")) {
                    // 헤더에 Cookie 값을 확인해서
                    // 로그인이 되어있지 않은 상태라면 login.html 화면으로 redirect 한다.
                    response302Header(dos,LOGIN_URL);
                    return;
                }
                // logined=true이면 유저 리스트를 보여준다.
                body = Files.readAllBytes(Paths.get(ROOT_URL + LIST_URL));
            }

            // 요구사항 7번
            if (method.equals("GET") && url.endsWith(".css")) {
                body = Files.readAllBytes(Paths.get(ROOT_URL + url));
                response200HeaderWithContentType(dos, body.length, "text/css");
                responseBody(dos, body);
                return;
            }

            response200Header(dos, body.length);
            responseBody(dos, body);

        } catch (IOException e) {
            log.log(Level.SEVERE,e.getMessage());
        }
    }
    private void response302HeaderWithCookie(DataOutputStream dos, String path) {
        try {
            dos.writeBytes("HTTP/1.1 302 Redirect \r\n");
            dos.writeBytes("Location: " + path + "\r\n");
            dos.writeBytes("Set-Cookie: logined=true" + "\r\n");
            // 헤더에 Cookie: logined=true를 추가

            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage());
        }
    }

    private void response200Header(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: text/html;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage());
        }
    }
    private void login(DataOutputStream dos, Map<String, String> queryParameter, User user) {
        if (user != null && user.getPassword().equals(queryParameter.get("password"))) {
            // 로그인 시 전달되는 유저와 repository에 있는 유저가 동일한지 확인
            response302HeaderWithCookie(dos,HOME_URL);
            // index.html 화면으로 redirect
            return;
        }
        // 로그인이 실패한다면 logined_failed.html로 redirect
        response302Header(dos,LOGIN_FAILED_URL);
    }

    // 요구사항 4번
    private void response302Header(DataOutputStream dos, String path) {
        try {
            dos.writeBytes("HTTP/1.1 302 Redirect \r\n");
            // response의 상태코드를 302로 변경한다.
            dos.writeBytes("Location: " + path + "\r\n");
            // 나머지 헤더는 Location 헤더만 필요하므로 삭제한다.
            // 파일의 위치가 아니라 클라이언트가 요청하는 url을 쓴다.
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage());
        }
    }
    private void responseBody(DataOutputStream dos, byte[] body) {
        try {
            dos.write(body, 0, body.length);
            dos.flush();
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage());
        }
    }
    private void response200HeaderWithContentType(DataOutputStream dos, int lengthOfBodyContent, String contentType) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: " + contentType + ";charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage());
        }
    }
}