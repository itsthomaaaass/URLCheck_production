package com.urlcheck.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.urlcheck.ai.deletion.PendingDeletions;
import com.urlcheck.check.CheckService;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlService;

/**
 * The application capabilities the assistant is allowed to use, bound to one
 * user.
 *
 * <p>One instance per chat request, built with the user id the session
 * resolved. That is what keeps the user out of the model's reach: no tool takes
 * a user id, so the model cannot ask for somebody else's rows however it is
 * prompted. Every method delegates to the existing services, so validation,
 * ownership checks and the URL-checking rules stay exactly where they are.
 *
 * <p>The tools return facts rather than verdicts, and leave the reasoning to the
 * model. Deletion is the exception to "tools act": it is only proposed here, and
 * carried out after the user confirms.
 */
public class MonitoredUrlTools {

    private final Long userId;
    private final MonitoredUrlService urlService;
    private final CheckService checkService;
    private final int maxUrlsPerCheck;
    private final List<PendingDeletions.Item> proposedDeletions = new ArrayList<>();

    public MonitoredUrlTools(Long userId, MonitoredUrlService urlService, CheckService checkService,
            int maxUrlsPerCheck) {
        this.userId = userId;
        this.urlService = urlService;
        this.checkService = checkService;
        this.maxUrlsPerCheck = maxUrlsPerCheck;
    }

    @Tool(name = "listUrls",
            description = "List every URL this user monitors, with its id, name, address, note and last"
                    + " stored status. Call this first for any question about the user's URLs, and to turn"
                    + " a name the user mentions into an id.")
    public ToolResult listUrls() {
        List<UrlSummary> urls = urlService.findAllForUser(userId).stream()
                .map(UrlSummary::of)
                .toList();
        return ToolResult.ok(urls);
    }

    /**
     * A live look, not a monitoring event: every URL is probed through
     * {@link CheckService#probe}, which neither compares the answer with the
     * stored snapshot nor writes. The scheduled checker owns the timeline, so
     * this tool can say whether a site answers and nothing about changes to it.
     */
    @Tool(name = "checkUrls",
            description = "Fetch every URL this user monitors right now and report which ones answer and"
                    + " which do not. Slow, because each URL is really requested over the network. A live"
                    + " look only: it writes nothing, records no event and is never compared with the URL's"
                    + " stored snapshot, so never describe its result as a change.")
    public ToolResult checkUrls() {
        List<MonitoredUrl> all = urlService.findAllForUser(userId);
        List<MonitoredUrl> toCheck = all.stream().limit(maxUrlsPerCheck).toList();

        List<UrlStatus> statuses = new ArrayList<>(toCheck.size());
        for (MonitoredUrl url : toCheck) {
            statuses.add(UrlStatus.of(url, checkService.probe(userId, url.getId())));
        }
        return ToolResult.ok(new CheckReport(statuses, all.size(), all.size() > toCheck.size()));
    }

    @Tool(name = "createUrl",
            description = "Start monitoring a new URL for this user. Rejections from the app's own"
                    + " validation (a missing name, a URL that is not http/https, an internal address)"
                    + " come back in the error field.")
    public ToolResult createUrl(
            @ToolParam(description = "Short label the user gave the URL, at most 100 characters") String name,
            @ToolParam(description = "Absolute address starting with http:// or https://") String url,
            @ToolParam(required = false,
                    description = "Optional free-text note, at most 1000 characters") String note) {
        MonitoredUrl toCreate = new MonitoredUrl();
        toCreate.setName(name);
        toCreate.setUrl(url);
        toCreate.setDescription(note);
        try {
            return ToolResult.ok(UrlSummary.of(urlService.create(userId, toCreate)));
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            return ToolResult.error(ex.getMessage());
        }
    }

    @Tool(name = "deleteUrl",
            description = "Propose deleting one of this user's monitored URLs, by id. Call this as soon as"
                    + " the user asks for a deletion, without asking them yourself first: nothing is deleted"
                    + " yet, because the app shows the user a confirmation for exactly this request. Never"
                    + " tell the user a URL was deleted.")
    public ToolResult deleteUrl(
            @ToolParam(description = "id of the URL to delete, taken from listUrls") long id) {
        MonitoredUrl found = urlService.findAllForUser(userId).stream()
                .filter(url -> url.getId() != null && url.getId() == id)
                .findFirst()
                .orElse(null);
        if (found == null) {
            return ToolResult.error("No monitored URL with id=" + id + " belongs to this user.");
        }
        if (proposedDeletions.stream().noneMatch(item -> item.urlId() == id)) {
            proposedDeletions.add(new PendingDeletions.Item(found.getId(), found.getName(), found.getUrl()));
        }
        return ToolResult.ok(new DeletionProposal(found.getId(), found.getName(), found.getUrl()));
    }

    /**
     * The deletions this request wants confirmed, in the order the model asked
     * for them. Read once the chat call returns; empty when nothing was
     * proposed.
     */
    public List<PendingDeletions.Item> proposedDeletions() {
        return List.copyOf(proposedDeletions);
    }

    /** One monitored URL, as the model sees it. */
    public record UrlSummary(
            long id,
            String name,
            String url,
            String note,
            String lastStatus,
            Integer lastHttpStatus,
            String lastCheckedAt,
            long changeCount) {

        static UrlSummary of(MonitoredUrl url) {
            return new UrlSummary(
                    url.getId(),
                    url.getName(),
                    url.getUrl(),
                    url.getDescription(),
                    url.getLastStatus(),
                    url.getLastHttpStatus(),
                    url.getLastCheckedAt() == null ? null : url.getLastCheckedAt().toString(),
                    url.getChangeCount() == null ? 0L : url.getChangeCount());
        }
    }

    /**
     * One probe result, as the model sees it: reachability only. There is no
     * change field on purpose, so a live look can never be turned into a claim
     * about the user's timeline.
     */
    public record UrlStatus(
            long id,
            String name,
            String url,
            boolean accessible,
            String status,
            Integer httpStatus,
            String errorType,
            long responseTimeMs) {

        static UrlStatus of(MonitoredUrl url, ProbeResult result) {
            return new UrlStatus(
                    url.getId(),
                    url.getName(),
                    url.getUrl(),
                    result.status() == CheckStatus.UP,
                    result.status().name(),
                    result.httpStatus(),
                    result.errorType() == null ? null : result.errorType().name(),
                    result.responseTimeMs());
        }
    }

    /** One check run, plus whether the report had to be cut short. */
    public record CheckReport(List<UrlStatus> results, int totalUrls, boolean truncated) {
    }

    /** A deletion the user has been asked to confirm. */
    public record DeletionProposal(long id, String name, String url) {
    }
}
