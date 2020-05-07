new Promise(function (resolve, reject) {
    var f = function () {
        window.scrollBy(0, window.innerHeight / 2);
        if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight) {
            resolve();
        } else {
            setTimeout(f, 1);
        }
    };
    f();
});