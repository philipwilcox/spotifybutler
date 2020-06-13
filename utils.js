module.exports = {

    /**
     * Returns true if sourceSet contains any of the items in targetSet.
     */
    setContainsAnyOf: function (sourceSet, targetSet) {
        return [...sourceSet].find(x => targetSet.has(x))
    }
};