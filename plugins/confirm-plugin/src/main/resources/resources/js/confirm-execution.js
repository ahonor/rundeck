/**
 * confirm-execution.js — Confirmation panel UI plugin
 *
 * Injected by ConfirmUIPlugin at the execution/show path.
 * Polls the confirm status API and renders an approval panel
 * when the execution is waiting for confirmation.
 */
(function () {
    'use strict';

    var pollTimer = null;
    var panelInjected = false;

    function getExecId() {
        try {
            if (typeof loadJsonData === 'function') {
                var info = loadJsonData('execInfoJSON');
                if (info && info.execId) return String(info.execId);
            }
        } catch (e) {}
        var m = window.location.pathname.match(/execution\/(?:show|follow)\/(\d+)/);
        return m ? m[1] : null;
    }

    function getRdBase() {
        try {
            if (window._rundeck && window._rundeck.rdBase) {
                return String(window._rundeck.rdBase).replace(/\/$/, '');
            }
        } catch (e) {}
        return '';
    }

    function getApiVersion() {
        try {
            if (window._rundeck && window._rundeck.apiVersion) return window._rundeck.apiVersion;
        } catch (e) {}
        return '41';
    }

    // Read CSRF token from the page's g:jsonToken element.
    // Rundeck embeds these as <span> elements with JSON content.
    function getCsrfHeaders() {
        var headers = {};
        // Try known token element IDs on the execution/show page
        var ids = ['exec_cancel_token', 'confirm_token'];
        for (var i = 0; i < ids.length; i++) {
            try {
                var el = document.getElementById(ids[i]);
                if (el) {
                    var data = JSON.parse(el.textContent);
                    if (data.TOKEN && data.URI) {
                        headers['X-RUNDECK-TOKEN-KEY'] = data.TOKEN;
                        headers['X-RUNDECK-TOKEN-URI'] = data.URI;
                        return headers;
                    }
                }
            } catch (e) {}
        }
        return headers;
    }

    function apiGet(path, cb) {
        var url = getRdBase() + '/api/' + getApiVersion() + path;
        var csrfHeaders = getCsrfHeaders();
        jQuery.ajax({
            url: url,
            type: 'GET',
            dataType: 'json',
            beforeSend: function (xhr) {
                xhr.setRequestHeader('X-Rundeck-Ajax', 'true');
                for (var k in csrfHeaders) {
                    xhr.setRequestHeader(k, csrfHeaders[k]);
                }
            },
            success: function (d) { cb(d, null); },
            error: function (xhr) { cb(null, xhr.status); }
        });
    }

    function apiPost(path, body, cb) {
        var url = getRdBase() + '/api/' + getApiVersion() + path;
        var csrfHeaders = getCsrfHeaders();
        jQuery.ajax({
            url: url,
            type: 'POST',
            dataType: 'json',
            contentType: 'application/json',
            data: JSON.stringify(body),
            beforeSend: function (xhr) {
                for (var k in csrfHeaders) {
                    xhr.setRequestHeader(k, csrfHeaders[k]);
                }
            },
            success: function (d) { cb(d, null); },
            error: function (xhr) {
                var msg = 'HTTP ' + xhr.status;
                try { msg = JSON.parse(xhr.responseText).error || xhr.responseText; } catch (e) {}
                cb(null, msg);
            }
        });
    }

    function findTarget() {
        return document.getElementById('execution-show-content')
            || document.querySelector('.execution-show')
            || document.querySelector('#section-content');
    }

    function renderPanel(status) {
        var html = '<div class="card" id="confirm-ui-panel" data-rundeck-confirm-ui="true" style="margin:10px 15px;">';
        html += '<div class="card-header">';
        html += '<h5 class="card-title"><i class="fas fa-pause-circle text-warning"></i> Waiting for Confirmation</h5>';
        html += '</div>';
        html += '<div class="card-content">';
        if (status.message) {
            html += '<p style="margin-bottom:10px;">' + escHtml(status.message) + '</p>';
        }
        html += '<div class="form-group">';
        html += '<label class="control-label">Comment (optional)</label>';
        html += '<input type="text" class="form-control confirm-panel-comment" id="confirm-comment" placeholder="e.g., LGTM after reviewing build logs">';
        html += '</div>';
        html += '<div class="confirm-panel-buttons">';
        html += '<button class="btn btn-success btn-sm" id="confirm-approve-btn"><i class="glyphicon glyphicon-ok"></i> Approve</button>';
        html += '<button class="btn btn-danger btn-sm" id="confirm-deny-btn"><i class="glyphicon glyphicon-remove"></i> Deny</button>';
        html += '</div>';
        html += '<div class="confirm-panel-status" id="confirm-status-msg"></div>';
        html += '</div>';
        html += '</div>';
        return html;
    }

    function renderResolved(decision, msg) {
        var icon = decision === 'approve' ? 'check text-success' : 'times text-danger';
        var label = decision === 'approve' ? 'Approved' : 'Denied';
        var html = '<div class="card" id="confirm-ui-panel" data-rundeck-confirm-ui="true" style="margin:10px 15px;">';
        html += '<div class="card-header">';
        html += '<h5 class="card-title"><i class="fas fa-' + icon + '"></i> ' + label + '</h5>';
        html += '</div>';
        if (msg) {
            html += '<div class="card-content"><p>' + escHtml(msg) + '</p></div>';
        }
        html += '</div>';
        return html;
    }

    function escHtml(s) {
        var d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    function submitDecision(decision) {
        var execId = getExecId();
        var comment = '';
        var commentEl = document.getElementById('confirm-comment');
        if (commentEl) comment = commentEl.value;

        var approveBtn = document.getElementById('confirm-approve-btn');
        var denyBtn = document.getElementById('confirm-deny-btn');
        if (approveBtn) approveBtn.disabled = true;
        if (denyBtn) denyBtn.disabled = true;

        var statusEl = document.getElementById('confirm-status-msg');
        if (statusEl) statusEl.textContent = 'Submitting...';

        apiPost('/execution/' + execId + '/confirm',
            { decision: decision, comment: comment },
            function (data, err) {
                if (err) {
                    if (statusEl) {
                        statusEl.innerHTML = '<span style="color:#d9534f;">Error: ' + escHtml(String(err)) + '</span>';
                    }
                    if (approveBtn) approveBtn.disabled = false;
                    if (denyBtn) denyBtn.disabled = false;
                    return;
                }
                // Replace panel with resolved state
                var panel = document.getElementById('confirm-ui-panel');
                if (panel) {
                    var label = decision === 'approve' ? 'Approved! Resuming...' : 'Denied.';
                    panel.outerHTML = renderResolved(decision, label);
                }
                // Reload after a moment so execution state updates
                setTimeout(function () { window.location.reload(); }, 3000);
            }
        );
    }

    function wireButtons() {
        var approveBtn = document.getElementById('confirm-approve-btn');
        var denyBtn = document.getElementById('confirm-deny-btn');
        if (approveBtn) {
            approveBtn.addEventListener('click', function () { submitDecision('approve'); });
        }
        if (denyBtn) {
            denyBtn.addEventListener('click', function () { submitDecision('deny'); });
        }
    }

    function injectPanel(status) {
        var target = findTarget();
        if (!target) return;
        var container = document.createElement('div');
        container.innerHTML = renderPanel(status);
        target.insertBefore(container.firstChild, target.firstChild);
        wireButtons();
        panelInjected = true;
    }

    function removePanel() {
        var panel = document.getElementById('confirm-ui-panel');
        if (panel) panel.remove();
        panelInjected = false;
    }

    function poll() {
        var execId = getExecId();
        if (!execId) return;

        // Check execution status first
        apiGet('/execution/' + execId, function (exec, err) {
            if (err || !exec) {
                // Can't reach API — retry
                pollTimer = setTimeout(poll, 3000);
                return;
            }

            if (exec.status === 'waiting') {
                // Execution is waiting — fetch confirm details and show panel
                apiGet('/execution/' + execId + '/confirm/status', function (data) {
                    if (data && data.waiting && !panelInjected) {
                        // Only render if this is the built-in confirm step
                        // (source is absent or 'confirm'). Third-party plugins
                        // set their own source and render their own UI.
                        if (data.source && data.source !== 'confirm') return;
                        // DOM convention: skip if another plugin already rendered
                        if (document.querySelector('[data-rundeck-confirm-ui]')) return;
                        injectPanel(data);
                    }
                });
                pollTimer = setTimeout(poll, 2000);
            } else if (exec.status === 'running') {
                // Still running — keep polling until it reaches waiting or completes
                if (panelInjected) removePanel();
                pollTimer = setTimeout(poll, 2000);
            } else {
                // Terminal state — stop polling, remove panel
                if (panelInjected) removePanel();
            }
        });
    }

    function init() {
        var execId = getExecId();
        if (!execId) return;
        // Start polling — the confirm status API will tell us when to show the panel
        poll();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
