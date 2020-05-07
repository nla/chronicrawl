var RealDate = Date;
Date = function () {
    if (arguments.length === 0) {
        return new RealDate(DATE);
    } else {
        return new (RealDate.bind.apply(Date, [null].concat(arguments)));
    }
};
Date.now = function () {
    return new Date();
};
var seed = DATE;
Math.random = function () {
    seed = (seed * 9301 + 49297) % 233280;
    return seed / 233280;
};