export default {

    /**
     * Returns true if sourceSet contains any of the items in targetSet.
     */
    setContainsAnyOf: function (sourceSet, targetSet) {
        return [...sourceSet].find(x => targetSet.has(x))
    },
    
    /**
     * Shuffles array in place. ES6 version from https://stackoverflow.com/questions/6274339/how-can-i-shuffle-an-array
     * @param {Array} a items An array containing the items.
     */
    shuffle: function (a) {
        for (let i = a.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [a[i], a[j]] = [a[j], a[i]];
        }
        return a;
    }
};