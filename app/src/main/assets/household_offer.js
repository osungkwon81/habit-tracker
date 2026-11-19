function readOffer(selectedOption, missingPage) {
    const url = location.href;
    const path = location.pathname.replace(/\/$/, "");
    const id = path.split("/").pop();
    const text = element => (element?.innerText || "").trim().replace(/\s+/g, " ");
    const number = value => {
        if (value == null || !/^-?\d+$/.test(String(value))) return null;
        const parsed = Number(value);
        return Number.isSafeInteger(parsed) ? parsed : null;
    };
    const available = total => Number.isSafeInteger(total) && total > 0 ? {url, total} : null;
    const unavailable = reason => ({url, unavailable: reason});
    const error = reason => ({url, error: reason});
    const deleted = /^(?:삭제된 상품입니다|판매가 종료된 상품입니다|현재 판매\s*중인 상품이 아닙니다|존재하지 않는 상품입니다|판매 종료된 상품입니다|상품을 찾을 수 없습니다|상품이 삭제되었습니다)[.!]?$/;
    const soldOut = /^(?:(?:현재 |일시 )?품절(?:된 상품입니다|입니다)?|재고\s*없음)[.!]?$/;
    const main = document.querySelector("main, [role=main], #content, #contents") || document.body;
    if (!main) return null;
    if (missingPage) {
        const message = text(main).slice(0, 2000);
        if (/(?:상품|페이지)(?:을|를|이|가)?\s*(?:찾을 수 없|존재하지 않|삭제되)|(?:삭제된|존재하지 않는) 상품/i.test(message))
            return unavailable("상품 페이지 없음");
        return error("상품 페이지를 찾을 수 없습니다 (HTTP 404). 판매 종료 여부는 확인되지 않았습니다.");
    }
    const pageNotice = Array.from(main.querySelectorAll("h1,h2,[role=alert],[class*=error] strong,[class*=error] p,[class*=not-found]"))
        .map(text).find(value => deleted.test(value));
    if (pageNotice) return unavailable("판매 종료");

    function merchant(product) {
        if (["DELETED", "END", "SALE_END"].includes(product.productStatusType) ||
            product.channelProductStatusType === "DELETE") return unavailable("판매 종료");
        if (["OUTOFSTOCK", "SOLD_OUT"].includes(product.productStatusType) || product.soldout === true || product.soldOut === true)
            return unavailable("품절");
        if (product.productStatusType && product.productStatusType !== "SALE") return error("판매 상태 확인 필요");
        if (product.channelProductDisplayStatusType && product.channelProductDisplayStatusType !== "ON") return error("전시 상태 확인 필요");
        let price = number(product.benefitsView?.discountedSalePrice ?? product.salePrice);
        if (price == null || price <= 0) return null;
        const options = product.optionCombinations || [];
        if (product.optionUsable || options.length) {
            const matches = options.filter(option => [option.optionName1, option.optionName2, option.optionName3]
                .filter(Boolean).join(" / ") === selectedOption || (!selectedOption && options.length === 1));
            if (matches.length !== 1) return error("선택한 구성의 옵션명을 확인해 주세요.");
            const option = matches[0];
            if (number(option.stockQuantity) === 0) return unavailable("선택 옵션 품절");
            if (option.usable === false || number(option.price) == null) return error("옵션 판매가 확인 필요");
            price += Number(option.price);
        } else {
            if (selectedOption) return error("등록한 옵션을 확인할 수 없습니다.");
            if (number(product.stockQuantity) === 0) return unavailable("품절");
        }
        const delivery = product.productDeliveryInfo;
        if (!delivery || price <= 0 || delivery.installationFee === true) return null;
        const fee = number(delivery.baseFee);
        if (delivery.deliveryFeeType === "FREE") return available(price);
        if (delivery.deliveryFeeType === "PAID" && fee != null && fee > 0) return available(price + fee);
        const threshold = number(delivery.freeConditionalAmount);
        if (delivery.deliveryFeeType === "CONDITIONAL_FREE" && threshold != null && threshold > 0) {
            if (price >= threshold) return available(price);
            if (fee != null && fee > 0) return available(price + fee);
        }
        return error("배송비 조건을 확인할 수 없습니다.");
    }

    if (location.hostname.endsWith("naver.com") && path.includes("/products/")) {
        const roots = [window.__PRELOADED_STATE__, window.__NEXT_DATA__?.props?.pageProps];
        // 추천 목록과 구분하기 위해 현재 URL의 채널 상품 번호가 일치하는 상품만 사용한다.
        const visited = new Set();
        while (roots.length && visited.size < 20000) {
            const node = roots.pop();
            if (!node || typeof node !== "object" || visited.has(node)) continue;
            visited.add(node);
            if (String(node.channelProductNo ?? node.id) === id &&
                (node.productDeliveryInfo || node.productStatusType) && node.salePrice != null) return merchant(node);
            for (const value of Object.values(node)) if (value && typeof value === "object") roots.push(value);
        }
        const header = main.querySelector("h1,h2");
        const box = header?.parentElement?.parentElement;
        if (box && Array.from(box.querySelectorAll("button:disabled,[role=status]")).some(el => soldOut.test(text(el))))
            return unavailable("품절");
        return null;
    }

    if (path.includes("/catalog/")) {
        const totals = new Set();
        const buy = Array.from(main.querySelectorAll("a,button")).filter(el =>
            /^(?:최저가\s*(?:사러가기|구매하기))$/.test(text(el)) && !el.closest("aside"));
        for (const action of buy) {
            let parent = action.parentElement;
            for (let level = 0; parent && level < 6; level++, parent = parent.parentElement) {
                const summary = text(parent);
                if (summary.length > 450) break;
                const marked = Array.from(summary.matchAll(/배송비\s*포함\s*(?:최저가?|판매가)\s*([\d,]+)\s*원/g));
                const free = Array.from(summary.matchAll(/최저가?\s*([\d,]+)\s*원\s*무료배송(?!\s*[(조건])/g));
                for (const match of [...marked, ...free]) totals.add(Number(match[1].replace(/,/g, "")));
                if (marked.length || free.length) break;
            }
        }
        if (totals.size === 1) return available([...totals][0]);
        if (!buy.length) {
            const heading = main.querySelector("h1,h2");
            const section = heading?.parentElement?.parentElement;
            if (section && Array.from(section.querySelectorAll("button:disabled,[role=status]")).some(el => soldOut.test(text(el))))
                return unavailable("품절");
        }
        return null;
    }

    // 쿠팡의 다른 옵션 가격·회원 혜택·반품 배송비는 현재 판매 구성의 금액에 포함하지 않는다.
    const heading = main.querySelector(".prod-buy-header__title") || main.querySelector("h1");
    if (!heading) return null;
    const title = text(heading);
    const body = main.innerText || "";
    const start = body.indexOf(heading.innerText.trim());
    if (start < 0 || !title) return null;
    const afterTitle = body.slice(start + heading.innerText.trim().length);
    const selected = afterTitle.split(/(?:모든 옵션 보기|배송비 포함|다른 옵션|장바구니 담기|함께 비교|상품상세상품평|\n[^\n]{1,25} × [^\n]{1,25}\n)/)[0].slice(0, 1400);
    const lines = selected.split(/\n+/).map(line => line.trim());
    const item = new URL(url).searchParams.get("itemId");
    const productCode = body.match(/쿠팡상품번호\s*[:：]\s*(\d+)\s*-\s*(\d+)/);
    if (productCode && (item ? productCode[2] !== item : productCode[1] !== id)) return error("판매 옵션이 변경됐습니다.");
    if (!item && /모든 옵션 보기/.test(afterTitle)) return error("옵션을 선택한 뒤 itemId가 포함된 쿠팡 주소를 등록해 주세요.");
    if (lines.some(value => deleted.test(value))) return unavailable("판매 종료");
    if (lines.some(value => soldOut.test(value))) return unavailable("품절");
    const won = value => Number(value.replace(/,/g, ""));
    const activePrice = main.querySelector(".prod-buy .prod-sale-price .total-price strong");
    const visiblePrices = lines.map(line => line.match(/^([\d,]+)\s*원(?:\s*\([^)]*당[^)]*\))?$/)).filter(Boolean);
    let price = null;
    if (activePrice && /^[\d,]+$/.test(text(activePrice)) && !/와우|쿠폰|카드/.test(text(activePrice.closest(".prod-sale-price"))))
        price = won(text(activePrice));
    else if (visiblePrices.length === 1 && !/쿠폰|와우회원|카드할인|회원할인/.test(selected)) price = won(visiblePrices[0][1]);
    if (!price) {
        // 단일 SKU의 일반 판매가만 허용한다. AggregateOffer의 최저가는 다른 구성일 수 있다.
        const current = new URL(url);
        for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
            let root;
            try { root = JSON.parse(script.textContent); } catch (invalidJson) { continue; }
            const products = Array.isArray(root) ? root : root?.["@graph"] || [root];
            for (const product of products) {
                if (product?.["@type"] !== "Product") continue;
                const offer = product.offers;
                if (offer?.["@type"] !== "Offer" || offer.priceCurrency !== "KRW" || !offer.url) continue;
                let offerAddress;
                try { offerAddress = new URL(offer.url, url); } catch (invalidUrl) { continue; }
                if (offerAddress.origin !== current.origin || offerAddress.pathname !== current.pathname ||
                    ["itemId", "vendorItemId"].some(key => current.searchParams.get(key) !== offerAddress.searchParams.get(key))) continue;
                if (/(?:OutOfStock|SoldOut|Discontinued)$/.test(offer.availability)) return unavailable("판매 종료 또는 품절");
                if (/InStock$/.test(offer.availability) && !offer.eligibleCustomerType) price = number(offer.price);
            }
        }
    }
    if (!price) return null;
    for (const cell of main.querySelectorAll("th,dt")) {
        if (text(cell) !== "배송비") continue;
        const shipping = text(cell.nextElementSibling).split(/\s*·?\s*도서산간 추가 배송비/)[0].trim();
        if (shipping === "무료배송" || shipping === "무료") return available(price);
        const paid = shipping.match(/^(?:배송비\s*)?([\d,]+)\s*원$/);
        if (paid) return available(price + won(paid[1]));
        const minimum = shipping.match(/^무료배송\s*\([^)]*?([\d,]+)\s*원 이상 구매 시\)$/);
        if (minimum && price >= won(minimum[1])) return available(price);
    }
    return null;
}
