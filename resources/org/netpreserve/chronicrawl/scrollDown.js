new Promise(function (resolve, reject) {
    var f = function () {
        window.scrollBy({ left: 0, top: window.innerHeight / 2, behavior: 'instant'});
        if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight) {
            resolve();
        } else {
            setTimeout(f, 1);
        }
    };
    f();
});