package cn.hnust.selection.service.impl;

import cn.hnust.selection.repository.TeacherOfficialProfileCacheRepository;
import cn.hnust.selection.vo.TeacherOfficialProfileVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up and caches only exact, public matches from the university faculty portal. */
@Service
public class OfficialFacultyProfileLookupService {
    private static final String FACULTY_HOST = "faculty.hnust.edu.cn";
    private static final String SOURCE_NAME = "湖南科技大学教师主页";
    private static final List<String> SECTION_HEADINGS = Arrays.asList(
        "基本情况", "学习经历", "工作经历", "承担课程", "主讲课程", "主持课题", "科研成果", "奖励荣誉",
        "研究方向", "学术服务", "代表性论文", "代表性成果", "社会兼职", "出版专著");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TeacherOfficialProfileCacheRepository cacheRepository;
    private final String searchUrl;

    public OfficialFacultyProfileLookupService(RestTemplateBuilder builder, ObjectMapper objectMapper,
        TeacherOfficialProfileCacheRepository cacheRepository,
        @Value("${selection.official-faculty.search-url:https://faculty.hnust.edu.cn//TPHP/data/search}") String searchUrl) {
        this.restTemplate = builder.setConnectTimeout(Duration.ofSeconds(2))
            .setReadTimeout(Duration.ofSeconds(4)).build();
        this.objectMapper = objectMapper;
        this.cacheRepository = cacheRepository;
        this.searchUrl = searchUrl;
    }

    public TeacherOfficialProfileVO getOrRefresh(Long teacherId, String fullName, String collegeName) {
        TeacherOfficialProfileVO cached = null;
        try {
            cached = cacheRepository.find(teacherId)
                .filter(item -> same(item.getMatchedFullName(), fullName) && same(item.getMatchedCollegeName(), collegeName))
                .map(this::toView).orElse(null);
        } catch (DataAccessException ignored) {
            // The public directory still works while an installation is applying the optional profile-cache migration.
        }
        if (cached != null && cached.getCachedAt() != null
            && cached.getCachedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(30))) return cached;

        TeacherOfficialProfileVO resolved = resolve(fullName, collegeName);
        if (resolved == null) return cached;
        try {
            cacheRepository.upsert(teacherId, fullName, collegeName, resolved);
        } catch (RuntimeException ignored) {
            // A portal outage or an unapplied optional cache migration must not block student directory reads.
        }
        return resolved;
    }

    public java.util.Map<Long, cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity> cachedProfiles(List<Long> teacherIds) {
        try {
            return cacheRepository.findByTeacherIds(teacherIds);
        } catch (DataAccessException ignored) {
            return Collections.emptyMap();
        }
    }

    public TeacherOfficialProfileVO cachedProfile(cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity cached,
        String fullName, String collegeName) {
        if (cached == null || !same(cached.getMatchedFullName(), fullName)
            || !same(cached.getMatchedCollegeName(), collegeName)) return null;
        return toView(cached);
    }

    private TeacherOfficialProfileVO resolve(String fullName, String collegeName) {
        if (blank(fullName) || blank(collegeName)) return null;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
            form.add("query", fullName.trim());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.ALL));
            headers.set(HttpHeaders.REFERER, "https://faculty.hnust.edu.cn/index/");
            String searchResponse = restTemplate.postForObject(searchUrl,
                new HttpEntity<MultiValueMap<String, String>>(form, headers), String.class);
            List<String> candidates = findProfileUrls(searchResponse, fullName);
            if (candidates.isEmpty() || candidates.size() > 3) return null;
            HttpHeaders pageHeaders = new HttpHeaders();
            pageHeaders.setAccept(Collections.singletonList(MediaType.ALL));
            pageHeaders.set(HttpHeaders.REFERER, "https://faculty.hnust.edu.cn/index/");
            TeacherOfficialProfileVO exactMatch = null;
            for (String candidate : candidates) {
                String profileUrl = officialProfileUrl(candidate);
                if (profileUrl == null) continue;
                String profileHtml = restTemplate.exchange(URI.create(profileUrl), HttpMethod.GET,
                    new HttpEntity<String>(pageHeaders), String.class).getBody();
                if (blank(profileHtml)) continue;
                TeacherOfficialProfileVO parsed = parseProfile(profileHtml, profileUrl, fullName, collegeName);
                if (parsed == null) continue;
                if (exactMatch != null) return null;
                exactMatch = parsed;
            }
            return exactMatch;
        } catch (RestClientException | IllegalArgumentException | java.io.IOException ignored) {
            return null;
        }
    }

    private List<String> findProfileUrls(String payload, String fullName) throws java.io.IOException {
        if (blank(payload)) return Collections.emptyList();
        Set<String> urls = new LinkedHashSet<String>();
        try {
            JsonNode root = objectMapper.readTree(payload);
            collectJsonCandidates(root, fullName, urls);
            collectHtmlCandidates(root, fullName, urls);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // The official endpoint may return an HTML fragment instead of JSON.
        }
        collectHtmlCandidates(Jsoup.parse(payload), fullName, urls);
        List<String> result = new ArrayList<String>();
        for (String url : urls) {
            String checked = officialProfileUrl(url);
            if (checked != null && !result.contains(checked)) result.add(checked);
        }
        return result;
    }

    private void collectJsonCandidates(JsonNode node, String fullName, Set<String> urls) {
        if (node == null) return;
        if (node.isObject() && containsDirectText(node, fullName)) {
            String url = findUrl(node);
            if (url != null) urls.add(url);
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) collectJsonCandidates(child, fullName, urls);
        }
    }

    private void collectHtmlCandidates(JsonNode node, String fullName, Set<String> urls) {
        if (node == null) return;
        if (node.isTextual() && node.asText().contains("<")) {
            collectHtmlCandidates(Jsoup.parse(node.asText()), fullName, urls);
            return;
        }
        if (node.isContainerNode()) for (JsonNode child : node) collectHtmlCandidates(child, fullName, urls);
    }

    private void collectHtmlCandidates(Document document, String fullName, Set<String> urls) {
        for (Element anchor : document.select("a[href]")) {
            if (!same(anchor.text(), fullName)) continue;
            urls.add(anchor.attr("abs:href").isEmpty() ? anchor.attr("href") : anchor.attr("abs:href"));
        }
    }

    private boolean containsDirectText(JsonNode node, String expected) {
        if (node == null) return false;
        if (node.isObject()) {
            java.util.Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                JsonNode value = values.next();
                if (value.isValueNode() && same(value.asText(), expected)) return true;
            }
        }
        return false;
    }

    private String findUrl(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String[] keys = {"url", "href", "link", "profileUrl", "homePage", "homepage", "detailUrl", "path"};
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> field = fields.next();
            for (String key : keys) {
                if (key.equalsIgnoreCase(field.getKey()) && field.getValue().isValueNode()
                    && !blank(field.getValue().asText())) return field.getValue().asText();
            }
        }
        return null;
    }

    private String officialProfileUrl(String raw) {
        if (blank(raw)) return null;
        try {
            URI uri = URI.create("https://" + FACULTY_HOST + "/").resolve(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !FACULTY_HOST.equalsIgnoreCase(uri.getHost())
                || !uri.getPath().startsWith("/pubtphp/") || !uri.getPath().contains("/chinese/")) return null;
            return uri.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private TeacherOfficialProfileVO parseProfile(String html, String profileUrl, String fullName, String collegeName) {
        Document document = Jsoup.parse(html, profileUrl);
        String allText = toReadableText(html);
        if (!allText.contains(fullName) || !collegeMatches(allText, collegeName)) return null;
        allText = allText.replace("学 历", "学历").replace("性 别", "性别");

        TeacherOfficialProfileVO result = new TeacherOfficialProfileVO();
        result.setProfileUrl(profileUrl);
        result.setSourceName(SOURCE_NAME);
        result.setCachedAt(LocalDateTime.now(ZoneOffset.UTC));
        result.setProfessionalTitle(labeledValue(allText, "职称"));
        result.setEducationLevel(labeledValue(allText, "学历"));
        result.setDepartment(labeledValue(allText, "系部所"));
        result.setTeachingLevel(labeledValue(allText, "执教层次"));
        result.setBiography(section(allText, "基本情况"));
        result.setEducationExperience(section(allText, "学习经历"));
        result.setWorkExperience(section(allText, "工作经历"));
        result.setCourses(firstNonBlank(section(allText, "承担课程"), section(allText, "主讲课程")));
        result.setResearchDirections(splitLines(section(allText, "研究方向")));
        result.setResearchAndAchievements(joinSections(allText, "主持课题", "科研成果", "奖励荣誉", "代表性论文", "代表性成果", "学术服务", "出版专著"));
        for (Element image : document.select("img[src]")) {
            String imageUrl = image.attr("abs:src");
            if (imageUrl.contains("/chinese/images/")) {
                result.setPhotoUrl(officialImageUrl(imageUrl));
                if (result.getPhotoUrl() != null) break;
            }
        }
        return result;
    }

    private String officialImageUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme()) && FACULTY_HOST.equalsIgnoreCase(uri.getHost())
                ? uri.toString() : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private String toReadableText(String html) {
        String breaks = html.replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|li|h[1-6]|tr|section|article)\\s*>", "\n");
        Document document = Jsoup.parse(breaks);
        document.select("script,style,noscript,svg,nav,footer,.share,.bdsharebuttonbox").remove();
        return scrub(document.body().wholeText().replace('\u00a0', ' ')
            .replaceAll("[\\t\\r ]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n").trim());
    }

    private String labeledValue(String text, String label) {
        Pattern pattern = Pattern.compile("(?:^|\\n|\\s)" + Pattern.quote(label) + "\\s*[：:]\\s*([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? scrub(matcher.group(1)) : "";
    }

    private String section(String text, String heading) {
        int start = text.lastIndexOf(heading);
        if (start < 0) return "";
        start += heading.length();
        while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '：' || text.charAt(start) == ':')) start++;
        int end = text.length();
        for (String candidate : SECTION_HEADINGS) {
            if (candidate.equals(heading)) continue;
            int next = text.lastIndexOf(candidate);
            if (next >= start && next < end) end = next;
        }
        return scrub(text.substring(start, end));
    }

    private String joinSections(String text, String... headings) {
        StringBuilder result = new StringBuilder();
        for (String heading : headings) {
            String value = section(text, heading);
            if (!blank(value)) {
                if (result.length() > 0) result.append('\n').append('\n');
                result.append(heading).append('\n').append(value);
            }
        }
        return result.toString();
    }

    private List<String> splitLines(String value) {
        if (blank(value)) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String line : value.split("[\\r\\n;；、]+")) {
            String normalized = line.replaceAll("^[\\s•·\\-—0-9.（）()]+", "").trim();
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
        }
        return result;
    }

    private String scrub(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("(?im)^.*(?:电话|手机|电子邮箱|邮箱|E-?mail)\\s*[：:].*$", "")
            .replaceAll("(?im)^.*(?:性\\s*别|Copyright|湘ICP备|公网安备|地址\\s*[：:]).*$", "")
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "")
            .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "")
            .replaceAll("(?im)^\\s*(?:办公室|办公地址|通讯地址)\\s*[：:]?[^\\n]*", "");
        return cleaned.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
    }

    private TeacherOfficialProfileVO toView(cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity cached) {
        TeacherOfficialProfileVO result = new TeacherOfficialProfileVO();
        result.setPhotoUrl(cached.getPhotoUrl());
        result.setProfessionalTitle(cached.getProfessionalTitle());
        result.setEducationLevel(cached.getEducationLevel());
        result.setDepartment(cached.getDepartment());
        result.setTeachingLevel(cached.getTeachingLevel());
        result.setResearchDirections(splitLines(cached.getResearchDirections()));
        result.setBiography(cached.getBiography());
        result.setEducationExperience(cached.getEducationExperience());
        result.setWorkExperience(cached.getWorkExperience());
        result.setCourses(cached.getCourses());
        result.setResearchAndAchievements(cached.getResearchAndAchievements());
        result.setProfileUrl(cached.getProfileUrl());
        result.setSourceName(SOURCE_NAME);
        result.setCachedAt(cached.getCachedAt());
        return result;
    }

    private boolean same(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }
    private boolean collegeMatches(String profileText, String collegeName) {
        if (profileText == null || blank(collegeName)) return false;
        String normalizedCollege = collegeName.trim();
        if (profileText.contains(normalizedCollege)) return true;
        // The official faculty portal uses the full unit name for this college; older rosters may use its short name.
        return "计算机学院".equals(normalizedCollege) && profileText.contains("计算机科学与工程学院");
    }
    private String firstNonBlank(String left, String right) { return blank(left) ? right : left; }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
