(function () {
    'use strict';

    var endpoint = '/events/log';
    var anonymousId = resolveAnonymousId();

    function track(eventName, options) {
        var name = clean(eventName, 80);
        if (!name) return;
        var payload = buildPayload(name, options || {});
        sendGa4(payload, options || {});
        sendEventLog(payload);
    }

    function buildPayload(eventName, options) {
        return {
            eventName: eventName,
            eventCategory: clean(options.eventCategory || 'interaction', 50),
            pagePath: clean(window.location.pathname + window.location.search, 500),
            referrer: clean(document.referrer, 500),
            elementText: clean(options.elementText, 160),
            anonymousId: anonymousId,
            reportOrderId: numberOrNull(options.reportOrderId),
            metadata: options.metadata || {}
        };
    }

    function sendGa4(payload, options) {
        if (options.skipGa4 || typeof window.gtag !== 'function') return;
        window.gtag('event', payload.eventName, {
            event_category: payload.eventCategory,
            page_path: payload.pagePath,
            report_order_id: payload.reportOrderId,
            element_text: payload.elementText
        });
    }

    function sendEventLog(payload) {
        var headers = {'Content-Type': 'application/json'};
        var csrfToken = metaContent('_csrf');
        var csrfHeader = metaContent('_csrf_header');
        if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
        fetch(endpoint, {
            method: 'POST',
            credentials: 'same-origin',
            keepalive: true,
            headers: headers,
            body: JSON.stringify(payload)
        }).catch(function () {});
    }

    function bindDomEvents() {
        track('page_view', {eventCategory: 'page'});

        document.addEventListener('click', function (event) {
            var target = event.target.closest('[data-analytics-event]');
            if (!target || target.tagName === 'FORM') return;
            track(target.getAttribute('data-analytics-event'), {
                eventCategory: target.getAttribute('data-analytics-category') || 'click',
                reportOrderId: target.getAttribute('data-analytics-report-order-id'),
                elementText: target.innerText || target.value || '',
                metadata: dataAttributes(target)
            });
        });

        document.addEventListener('submit', function (event) {
            var form = event.target.closest('form[data-analytics-event]');
            if (!form) return;
            track(form.getAttribute('data-analytics-event'), {
                eventCategory: form.getAttribute('data-analytics-category') || 'form',
                reportOrderId: form.getAttribute('data-analytics-report-order-id'),
                metadata: dataAttributes(form)
            });
        });
    }

    function dataAttributes(element) {
        var metadata = {};
        Object.keys(element.dataset || {}).forEach(function (key) {
            if (key.indexOf('analytics') !== 0) return;
            metadata[key] = clean(element.dataset[key], 500);
        });
        return metadata;
    }

    function resolveAnonymousId() {
        try {
            var key = 'precustomer_anonymous_id';
            var existing = window.localStorage.getItem(key);
            if (existing) return clean(existing, 80);
            var created = 'anon_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 12);
            window.localStorage.setItem(key, created);
            return created;
        } catch (ignored) {
            return null;
        }
    }

    function metaContent(name) {
        var meta = document.querySelector('meta[name="' + name + '"]');
        return meta ? meta.getAttribute('content') : null;
    }

    function clean(value, maxLength) {
        if (value === undefined || value === null) return null;
        var text = String(value).trim();
        if (!text) return null;
        return text.length <= maxLength ? text : text.slice(0, maxLength);
    }

    function numberOrNull(value) {
        if (value === undefined || value === null || value === '') return null;
        var number = Number(value);
        return Number.isFinite(number) ? number : null;
    }

    window.pcrAnalytics = {track: track};

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bindDomEvents);
    } else {
        bindDomEvents();
    }
})();
